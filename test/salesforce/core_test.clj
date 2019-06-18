(ns salesforce.core-test
  (:use clojure.test
        salesforce.core))

(defmacro with-private-fns [[ns fns] & tests]
  "Refers private fns from ns and runs tests in context."
  `(let ~(reduce #(conj %1 %2 `(ns-resolve '~ns '~%2)) [] fns)
     ~@tests))

(def sample-auth
  {:id           "https://login.salesforce.com/id/1234",
   :issued_at    "1367488271359",
   :instance_url "https://na15.salesforce.com",
   :signature    "SIG",
   :access_token "ACCESS"})

(deftest with-version-test
  (testing "should set +version+ to a given string"
    (is (= "26.0" (with-version "26.0" @+version+)))))

(def well-formed-auth-app-data
  {:client-id      "my-ID"
   :client-secret  "my-SECRET"
   :username       "my-USERNAME"
   :password       "my-PASSWORD"
   :security-token "my-TOKEN"})

(def well-formed-client-config-empty {})

(deftest test-make-params-for-auth-request
  (testing "accepts nil 2nd arg"
    (is
     (=
      {:form-params {:grant_type    "password"
                     :client_id     "my-ID"
                     :client_secret "my-SECRET"
                     :format        "json",
                     :username      "my-USERNAME"
                     :password      "my-PASSWORDmy-TOKEN"}}
      (make-params-for-auth-request well-formed-auth-app-data nil))))

  (testing "accepts empty 2nd arg"
    (is
     (=
      {:form-params {:grant_type    "password"
                     :client_id     "my-ID"
                     :client_secret "my-SECRET"
                     :format        "json",
                     :username      "my-USERNAME"
                     :password      "my-PASSWORDmy-TOKEN"}}
      (make-params-for-auth-request well-formed-auth-app-data {}))))

  (testing "includes all keys from 2nd arg when provided"
    (is
     (=
      {:form-params                {:grant_type    "password"
                                    :client_id     "my-ID"
                                    :client_secret "my-SECRET"
                                    :format        "json",
                                    :username      "my-USERNAME"
                                    :password      "my-PASSWORDmy-TOKEN"}
       :connection-timeout         10
       :connection-request-timeout 10
       :foo                        5}
      (make-params-for-auth-request well-formed-auth-app-data
                                    {:connection-timeout 10 :connection-request-timeout 10 :foo 5})))))

(def well-formed-auth-token-mock
  {:instance_url "https://salesforce.localhost" :access_token "my_token"})

(def expected-auth-header-from-well-formed-auth-token-mock
  {"Authorization" "Bearer my_token"})

(deftest test-soql-prepare
  (testing "should generate expected params "
    (is (= {:method :get :url "https://salesforce.localhost/services/data/v39.0/query?q=SELECT+foo+from+Account" :headers expected-auth-header-from-well-formed-auth-token-mock}
           (soql-prepare "SELECT foo from Account" well-formed-auth-token-mock)))))

;; Private functions


(with-private-fns [salesforce.core [gen-query-url]]
  (deftest gen-query-url-test
    (testing "should generate a valid url for salesforce.com"
      (let [url (gen-query-url "20.0" "SELECT name from Account")]
        (is (= url "/services/data/v20.0/query?q=SELECT+name+from+Account"))))))

(with-private-fns [salesforce.core [prepare-request]]
  (deftest test-prepare-request
    (testing "should generate expected params when no client params specified"
      (is (= {:method :get
              :url "https://salesforce.localhost/foo/bar/baz"
              :headers expected-auth-header-from-well-formed-auth-token-mock}
             (prepare-request :get "/foo/bar/baz" well-formed-auth-token-mock)))))

  (testing "should generate expected params when client params are specified"
    (is (= {:method :get
            :url "https://salesforce.localhost/foo/bar/baz"
            :headers expected-auth-header-from-well-formed-auth-token-mock
            :connection-timeout 5000}
           (prepare-request :get "/foo/bar/baz" well-formed-auth-token-mock {:connection-timeout 5000})))))

