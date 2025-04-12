# aws-api-ext

Extensions for the Cognitect AWS API library (`cognitect.aws/api`) that address common issues and provide enhanced functionality.

[![Clojars Project](https://img.shields.io/clojars/v/dev.kwill/aws-api-ext.svg)](https://clojars.org/dev.kwill/aws-api-ext)

## Features

- Improved error handling and retry logic for AWS services
- Standardized anomaly categorization
- Automatic SSL exception retries
- Enhanced credentials handling with support for:
  - Profile-based credentials
  - AssumeRole functionality
- Service-specific error handling for various AWS APIs

## Installation

Add the following dependency to your `deps.edn`:

```clojure
;; Required
dev.kwill/aws-api-ext {:mvn/version "0.1.8"}
com.cognitect.aws/api {:mvn/version "0.8.589"}

;; Only needed if using profile credentials with assume role
com.cognitect.aws/sts {:mvn/version "847.2.1387.0"}
```

## Usage

### Basic Client

Create AWS clients with improved retry logic:

```clojure
(require '[kwill.aws-api :as aws])

;; Create an S3 client with default credentials provider
(def s3-client (aws/client {:api :s3}))

;; Invoke operations
(aws/invoke s3-client {:op :ListBuckets})

;; Async operations
(require '[clojure.core.async :refer [<!]])
(let [response-chan (aws/invoke-async s3-client {:op :ListBuckets})]
  (<! response-chan))
```

### Profile Credentials

Use credentials from specific AWS CLI profiles:

```clojure
(require '[kwill.aws-api :as aws])

;; Create client using a specific profile
(def s3-client (aws/client {:api :s3
                           :profile "my-profile"}))
```

### AssumeRole Support

The library simplifies assuming IAM roles when using profile credentials:

```clojure
;; In your ~/.aws/config file:
;; [profile my-role-profile]
;; role_arn = arn:aws:iam::123456789012:role/my-role
;; source_profile = default
;; region = us-west-2

;; In your code:
(def role-client (aws/client {:api :s3
                             :profile "my-role-profile"}))
```

## License

Copyright © 2023-2025 Kenny Williams

Distributed under the [MIT License](LICENSE).
