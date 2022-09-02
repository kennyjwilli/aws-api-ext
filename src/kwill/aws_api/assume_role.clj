(ns kwill.aws-api.assume-role
  "Fetch aws-api creds map using the AssumeRole op."
  (:require
    [clojure.tools.logging :as log]
    [cognitect.aws.credentials :as credentials]))

(let [invoke (delay @(requiring-resolve 'kwill.aws-api/invoke))]
  (defn fetch-creds
    [sts-client {:keys [role-arn
                        external-id
                        role-session-name]}]
    (let [role-session-name (or role-session-name (System/getProperty "user.name"))
          resp (@invoke
                 sts-client {:op      :AssumeRole
                             :request (cond-> {:RoleArn         role-arn
                                               :RoleSessionName role-session-name}
                                        external-id
                                        (assoc :ExternalId external-id))})]
      (when (:cognitect.anomalies/category resp)
        (log/info {:tag         ::assume-role-anomaly
                   :role-arn    role-arn
                   :external-id external-id
                   :anomaly     resp}))
      (if (:cognitect.anomalies/category resp)
        resp
        (when-let [creds (:Credentials resp)]
          {:aws/access-key-id     (:AccessKeyId creds)
           :aws/secret-access-key (:SecretAccessKey creds)
           :aws/session-token     (:SessionToken creds)
           ::credentials/ttl      (credentials/calculate-ttl creds)})))))
