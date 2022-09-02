(ns kwill.aws-api.credentials
  (:require
    [cognitect.aws.client.shared :as shared]
    [cognitect.aws.credentials :as credentials]
    [kwill.aws-api.profile-credentials :as profile-credentials]))

(defn default-credentials-provider
  "Returns a chain-credentials-provider with (in order):

    environment-credentials-provider
    system-property-credentials-provider
    profile-credentials-provider
    container-credentials-provider
    instance-profile-credentials-provider

  Alpha. Subject to change."
  [http-client]
  (credentials/chain-credentials-provider
    [(credentials/environment-credentials-provider)
     (credentials/system-property-credentials-provider)
     (profile-credentials/provider)                         ;; replaced credentials/profile-credentials-provider
     (credentials/container-credentials-provider http-client)
     (credentials/instance-profile-credentials-provider http-client)]))

(def ^:private shared-credentials-provider
  (delay (default-credentials-provider (shared/http-client))))

(defn get-shared-credentials-provider
  []
  @shared-credentials-provider)
