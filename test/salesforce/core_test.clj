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

(testing "make-params-for-auth-request"
  (testing "accepts nil 2nd arg"
    (is
     (=
      {:form-params {:grant_type    "password"
                     :client_id     "my-ID"
                     :client_secret "my-SECRET"
                     :format         "json",
                     :username      "my-USERNAME"
                     :password      "my-PASSWORDmy-TOKEN"}}
      (make-params-for-auth-request well-formed-auth-app-data nil))))

  (testing "accepts empty 2nd arg"
    (is
     (=
      {:form-params {:grant_type "password"
                     :client_id     "my-ID"
                     :client_secret "my-SECRET"
                     :format         "json",
                     :username      "my-USERNAME"
                     :password      "my-PASSWORDmy-TOKEN"}}
      (make-params-for-auth-request well-formed-auth-app-data {}))))

  (testing "includes all keys from 2nd arg when provided"
    (is
     (=
      {:form-params                {:grant_type "password"
                                    :client_id     "my-ID"
                                    :client_secret "my-SECRET"
                                    :format         "json",
                                    :username      "my-USERNAME"
                                    :password      "my-PASSWORDmy-TOKEN"}
       :connection-timeout         10
       :connection-request-timeout 10
       :foo                        5}
      (make-params-for-auth-request well-formed-auth-app-data
                                    {:connection-timeout 10 :connection-request-timeout 10 :foo 5})))))

()

;; Private functions

(with-private-fns [salesforce.core [gen-query-url]]
  (deftest gen-query-url-test
    (testing "should generate a valid url for salesforce.com"
      (let [url (gen-query-url "20.0" "SELECT name from Account")]
        (is (= url "/services/data/v20.0/query?q=SELECT+name+from+Account"))))))

