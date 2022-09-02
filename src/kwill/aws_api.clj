(ns kwill.aws-api
  "Cognitect AWS API wrapper namespace fixing numerous recurring issues with upstream."
  (:require
    [clojure.core.async :as async]
    [cognitect.aws.client.api :as aws]
    [cognitect.aws.client.api.async :as aws.async]
    [cognitect.aws.retry :as aws.retry]
    [kwill.aws-api.credentials :as kwill.credentials]
    [kwill.aws-api.profile-credentials :as profile-credentials])
  (:import (javax.net.ssl SSLException SSLHandshakeException)))

(defprotocol IClient
  (-invoke-async [_ op-map])
  (-invoke [_ op-map]))

(defn __type-pred
  [allowed-types]
  #(contains? (set allowed-types) (:__type %)))

(def type-ThrottlingException? (__type-pred #{"ThrottlingException"}))

(def retriable-__type-pred
  (__type-pred #{"ThrottlingException" "ServiceUnavailableException"}))

(defn error-response-Throttling?
  [anomaly]
  (= "Throttling" (get-in anomaly [:ErrorResponse :Error :Code])))

(def default-api->retriable-fn
  "Map of AWS API to a fn that will be called to see if the request should be retried
  based on the HTTP response. This is needed because various AWS APIs indicate
  failures in non-standard ways."
  {:application-autoscaling retriable-__type-pred
   :dynamodb                #(= "com.amazon.coral.availability#ThrottlingException" (:__type %))
   :workspaces              type-ThrottlingException?
   :pricing                 type-ThrottlingException?
   ;; speculating how ECS returns ThrottlingExceptions based on documentation, could not test it.
   :ecs                     retriable-__type-pred
   :cloudtrail              type-ThrottlingException?
   :savingsplans            type-ThrottlingException?
   :cloudformation          error-response-Throttling?
   :ec2                     error-response-Throttling?
   :monitoring              error-response-Throttling?
   :organizations           #(= "TooManyRequestsException" (:__type %))})

(def default-status->anomaly-category
  {400 :cognitect.anomalies/incorrect
   401 :cognitect.anomalies/forbidden
   403 :cognitect.anomalies/forbidden
   404 :cognitect.anomalies/not-found
   405 :cognitect.anomalies/unsupported
   409 :cognitect.anomalies/conflict
   429 :cognitect.anomalies/busy

   501 :cognitect.anomalies/unsupported
   503 :cognitect.anomalies/busy
   504 :cognitect.anomalies/unavailable
   505 :cognitect.anomalies/unsupported})

;; Occasionally a javax.net.ssl.SSLException gets thrown in production. Typically,
;; a retry will fix this issue. We catch that here. This issue is being tracked
;; on GitHub as well.
;; https://github.com/cognitect-labs/aws-api/issues/139
(defn ssl-exception?
  [x]
  (and (= :cognitect.anomalies/fault (:cognitect.anomalies/category x))
    (let [t (:cognitect.http-client/throwable x)]
      (or
        (instance? SSLException t)
        (instance? SSLHandshakeException t)))))

(defn default-retry-fn
  [api]
  (let [api-specific-retry-fn (get default-api->retriable-fn api (constantly false))]
    (fn [resp]
      (or (aws.retry/default-retriable? resp)
        (api-specific-retry-fn resp)
        (ssl-exception? resp)))))

(defn client
  [config]
  (let [config (cond-> (merge
                         {:credentials-provider (kwill.credentials/get-shared-credentials-provider)
                          :retriable?           (default-retry-fn (:api config))}
                         config)
                 (and (nil? (:credentials-provider config))
                   (:profile config))
                 (assoc
                   :credentials-provider
                   (profile-credentials/provider (:profile config))))]
    (vary-meta (aws/client config)
      merge
      {::config config})))

(defn mock-client
  [config & {:keys [handler]}]
  (with-meta
    (reify IClient
      (-invoke-async [c op-map]
        (handler c op-map))
      (-invoke [c op-map]
        (handler c op-map)))
    {::config config}))

(defn client-config
  "Returns the config passed when constructing this client. Returns nil if client
  was not constructed with the client function in this namespace."
  [client]
  (::config (meta client)))

(def retryable-categories #{:cognitect.anomalies/unavailable :cognitect.anomalies/interrupted :cognitect.anomalies/busy})

(defn retryable-category?
  [category]
  (contains? retryable-categories category))

(defn canonical-anomaly-category
  [api aws-api-anomaly]
  (or
    ;; Already retryable, leave category as is.
    (when (retryable-category? (:cognitect.anomalies/category aws-api-anomaly)) (:cognitect.anomalies/category aws-api-anomaly))
    ;; Manually overridden retryable by api name, ensure category is set
    (when-let [retriable? (get default-api->retriable-fn api)]
      (when (retriable? aws-api-anomaly)
        :cognitect.anomalies/busy))
    ;; API agnostic category corrections
    (when-let [anom-type (:__type aws-api-anomaly)]
      (case anom-type
        ("AccessDeniedException"
          "FailedResourceAccessException"
          "com.amazon.coral.service#AccessDeniedException")
        :cognitect.anomalies/forbidden
        ("ObjectNotFoundException")
        :cognitect.anomalies/not-found
        nil))

    ;; Else, attempt to correct using http status
    (default-status->anomaly-category
      (get-in (meta aws-api-anomaly) [:http-response :status]))))

(defn aws-request-id
  "Returns the AWS request id from the raw AWS http response data."
  [response]
  (or
    (get-in response [:headers "x-amzn-requestid"])))

(defn canonicalize-anomaly
  [api aws-api-anomaly]
  (let [category (canonical-anomaly-category api aws-api-anomaly)
        request-id (aws-request-id (:http-response (meta aws-api-anomaly)))]
    (cond-> aws-api-anomaly
      (and category (not= category (:cognitect.anomalies/category aws-api-anomaly)))
      (assoc
        :cognitect.anomalies/category category
        ::original-category (:cognitect.anomalies/category aws-api-anomaly))
      request-id
      (assoc ::aws-request-id request-id))))

(defn- invoke-async*
  [client op-map]
  (async/go
    (let [response (aws.async/invoke client op-map)]
      (if (:cognitect.anomalies/category response)
        (canonicalize-anomaly (get-in (meta client) [::config :api]) response)
        response))))

(defn invoke-async
  "Same as aws.async/invoke"
  [client op-map]
  (-invoke-async client op-map))

(defn invoke
  "Same as aws/invoke"
  [client op-name]
  (-invoke client op-name))

(extend-type cognitect.aws.client.Client
  IClient
  (-invoke-async [this op-map]
    (invoke-async* this op-map))
  (-invoke [this op-map]
    (aws/invoke this op-map)))
