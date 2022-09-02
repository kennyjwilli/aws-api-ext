(ns kwill.aws-api.profile-credentials
  "Profile credentials provider with AssumeRole support."
  (:require
    [clojure.java.io :as io]
    [clojure.tools.logging :as log]
    [cognitect.aws.config :as config]
    [cognitect.aws.credentials :as credentials]
    [cognitect.aws.util :as u]
    [kwill.aws-api.assume-role :as assume-role]))

(defn get-source-profile-order
  [config profile-name {:keys [max-iterations]
                        :or   {max-iterations 10}}]
  (loop [profile-name profile-name
         sources (list)
         iter 0]
    (when (>= iter max-iterations)
      (throw
        (let [msg (format "Cannot nest more than %s profiles." max-iterations)]
          (ex-info msg {:cognitect.anomalies/category :cognitect.anomalies/unsupported
                        :cognitect.anomalies/message  msg
                        :max-iterations               max-iterations}))))
    (let [profile (get config profile-name)
          source-profile-name (get profile "source_profile")]
      (if (nil? source-profile-name)
        (cons profile sources)
        (recur source-profile-name (cons profile sources) (inc iter))))))

(let [client (delay @(requiring-resolve 'kwill.aws-api/client))]
  (defn fetch-nested-profiles-creds-map
    [profiles]
    (reduce
      (fn [creds-map [start-profile target-profile]]
        (let [sts-client (@client
                           (cond-> {:api :sts}
                             (:region start-profile)
                             (assoc :region (:region start-profile))
                             creds-map
                             (assoc
                               :credentials-provider
                               (reify
                                 credentials/CredentialsProvider
                                 (fetch [_] creds-map)))))
              next-creds (assume-role/fetch-creds sts-client
                           {:role-arn          (get target-profile "role_arn")
                            :external-id       (get target-profile "external_id")
                            :role-session-name (get target-profile "role_session_name")})]
          next-creds))
      {:aws/access-key-id     (-> profiles (first) (get "aws_access_key_id"))
       :aws/secret-access-key (-> profiles (first) (get "aws_secret_access_key"))}
      (partition 2 1 profiles))))

(defn read-aws-profiles-from-file
  [config-file creds-file]
  (merge
    (when (.exists config-file)
      (try
        (config/parse config-file)
        (catch Exception _ (log/warn "Cannot parse AWS config file."))))
    (when (.exists creds-file)
      (try
        (config/parse creds-file)
        (catch Exception _ (log/warn "Cannot parse AWS credentials file."))))))

(defn get-credentials-from-profile
  [profiles-map profile-name]
  (let [profile (get profiles-map profile-name)]
    (credentials/valid-credentials
      {:aws/access-key-id     (get profile "aws_access_key_id")
       :aws/secret-access-key (get profile "aws_secret_access_key")
       :aws/session-token     (get profile "aws_session_token")}
      "aws profiles file")))

(defn get-credentials-from-assume-role-profile
  [profiles-map profile-name]
  (when (get-in profiles-map [profile-name "role_arn"])
    (let [profiles (get-source-profile-order profiles-map profile-name {})
          creds (fetch-nested-profiles-creds-map profiles)]
      (credentials/valid-credentials creds "aws config file"))))

(defn provider
  "Return credentials in an AWS configuration profile.

  Arguments:

  profile-name  string  The name of the profile in the file. (default: default)
  f             File    The profile configuration file. (default: ~/.aws/credentials)

  https://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/credentials.html
    Parsed properties:

    aws_access_key        required
    aws_secret_access_key required
    aws_session_token     optional

  Alpha. Subject to change."
  ([]
   (provider (or (u/getenv "AWS_PROFILE")
               (u/getProperty "aws.profile")
               "default")))
  ([profile-name]
   (provider profile-name
     {:config-file (or (io/file (u/getenv "AWS_CONFIG_FILE"))
                     (io/file (u/getProperty "user.home") ".aws" "config"))
      :creds-file  (or (io/file (u/getenv "AWS_CREDENTIAL_PROFILES_FILE"))
                     (io/file (u/getProperty "user.home") ".aws" "credentials"))}))
  ([profile-name {:keys [config-file creds-file]}]
   (credentials/cached-credentials-with-auto-refresh
     (reify credentials/CredentialsProvider
       (fetch [_]
         (let [profiles-map (read-aws-profiles-from-file config-file creds-file)]
           (or
             (get-credentials-from-profile profiles-map profile-name)
             (get-credentials-from-assume-role-profile profiles-map profile-name))))))))
