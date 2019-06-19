(ns salesforce.core
  (:require
   [clojure.string :as str]
   [cheshire.core :as json]
   [clj-http.client :as http]))

(def ^:dynamic +token+ nil)

(defmacro with-token
  [token & forms]
  `(binding [+token+ ~token]
     (do ~@forms)))

(defn-  as-json
  "Takes a Clojure map and returns a JSON string"
  [map]
  (json/generate-string map))

(defn conf [f]
  (ref (binding [*read-eval* false]
         (with-open [r (clojure.java.io/reader f)]
           (read (java.io.PushbackReader. r))))))

(def ^:private limit-info (atom {}))

(defn- extract-limit-info-header [response]
  "Extracts the Salesforce limit info header from response, if available. Note that auth responses do not include it."
  (get-in response [:headers "Sforce-Limit-Info"]))

(defn- parse-limit-info [v]
  "Parses out used and available numbers from string input that looks like api-usage=286479/645000"
  (let [[used available]
        (->> (-> (str/split v #"=")
                 (second)
                 (str/split #"/"))
             (map #(Integer/parseInt %)))]
    {:used used
     :available available}))

(defn- store-new-limit-info [new-limit-info]
  "Stores updated limit info in atom and returns it"
  (reset! limit-info new-limit-info))

(defn- extract-and-store-limit-info! [response]
  "Extracts limit info from response header, stores it in atom, and returns it"
  (some-> response
          (extract-limit-info-header)
          (parse-limit-info)
          (store-new-limit-info)))

(defn read-limit-info
  "Deref the value of the `limit-info` atom which is
   updated with each call to the API. Returns a map,
   containing the used and available API call counts:
   {:used 11 :available 15000}"
  []
  @limit-info)

(defn- prepare-request
  "Part 1 of 2 of replacement of 'request' - A pure function that prepares input for a call to perform-request"
  [method url token & [params]]
  (let [base-url (:instance_url token)
        full-url (str base-url url)
        client-params (merge (or params {})
                             {:method method
                              :url full-url
                              :headers {"Authorization" (str "Bearer " (:access_token token))}})]
    client-params))

(defn- perform-request [client-params]
  "Part 2 of 2 of replacement of 'request' - An impure function that uses output of 'prepare-request to make actual http call via clj-http.
  Returns a map: {:api-result the-result-from-salesforce :limit-info a_limit_info_map
  Note that limit_info_map will be empty for auth requests. For other requests, it should be in the form {:used 15000 :available 623000}
  "
  (let [resp (http/request client-params)
        limit-info (or (extract-and-store-limit-info! resp) {})
        api-result (-> resp
                       :body
                       (json/decode true))]
    {:api-result api-result :limit-info limit-info}))

(defn- request
  "DEPRECATED. Use the combination of `prepare-request` and `peform-request` instead.
   Make a HTTP request to the Salesforce.com REST API
   Make a HTTP request to the Salesforce.com REST API
   Token is the full map returned from (auth! @conf)"
  [method url token & [params]]
  (let [base-url (:instance_url token)
        full-url (str base-url url)
        resp (try (http/request
                   (merge (or params {})
                          {:method method
                           :url full-url
                           :headers {"Authorization" (str "Bearer " (:access_token token))}}))
                  (catch Exception e (:body (ex-data e))))]
    (-> (get-in resp [:headers "sforce-limit-info"]) ;; Record limit info in atom
        (parse-limit-info)
        ((partial reset! limit-info)))
    (-> resp
        :body
        (json/decode true))))

(defn- safe-request
  "Perform a request but catch any exceptions"
  [method url token & params]
  (try
    (with-meta
      (request method url token params)
      {:method method :url url :token token})
    (catch Exception e (.toString e))))

(defn auth-prepare
  "Prepare a request to get auth info from Salesforce
  app_data is a map in the form
  - client-id ID
  - client-secret SECRET
  - username USERNAME
  - password PASSWORD
  - security-token TOKEN
  - login-host HOSTNAME (optional; default login.salesforce.com)
  http-client-config is an optional map of options accepted by clj-http/core/request, such as keys: connection-timeout connection-request-timeout connection-manager
  "
  [{:keys [client-id client-secret username password security-token login-host] :as app_data} & [http-client-config]]
  (let [hostname (or login-host "login.salesforce.com")
        auth-url (format "https://%s/services/oauth2/token" hostname)
        salesforce-params {:grant_type "password"
                           :client_id client-id ; note conversion of hyphen to underscore in key name
                           :client_secret client-secret ; note conversion of hyphen to underscore in key name
                           :username username
                           :password (str password security-token)
                           :format "json"}
        client-params (merge (or http-client-config {})
                             {:form-params salesforce-params}
                             {:method :post :url auth-url})]
    client-params))

(defn auth!
  "Get security token and auth info from Salesforce
   app_data is a map in the form
   - client-id ID
   - client-secret SECRET
   - username USERNAME
   - password PASSWORD
   - security-token TOKEN
   - login-host HOSTNAME (default login.salesforce.com)
   http-client-config is an optional map of options accepted by clj-http/core/request, such as keys: connection-timeout connection-request-timeout connection-manager
   Returns a map: {:api-result the-result-from-salesforce :limit-info {}} (auth responses do not include the limit info header)
   "
  [app_data & [http-client-config]]
  (-> (auth-prepare app_data http-client-config)
      (perform-request)))

;; Salesforce API version information

(defn all-versions
  "Lists all available versions of the Salesforce REST API"
  []
  (->> (http/get "http://na1.salesforce.com/services/data/")
       :body
       (json/parse-string)))

(defn latest-version
  "What is the latest API version?"
  []
  (->> (last (all-versions))
       (map (fn [[k _]] [(keyword k) _]))
       (into {})
       :version))

(defonce ^:dynamic +version+ (atom "39.0"))

(defn set-version! [v]
  (reset! +version+ v))

(def latest-version*
  "Memoized latest-version, used by (with-latest-version) macro"
  (memoize latest-version))

(defmacro with-latest-version [& forms]
  `(binding [+version+ (atom (latest-version*))]
     (do ~@forms)))

(defmacro with-version [v & forms]
  `(binding [+version+ (atom ~v)] (do ~@forms)))

;; Resources

(defn resources [token]
  (request :get (format "/services/data/v%s/" @+version+) token))

(defn so->objects
  "Lists all of the available sobjects"
  [token]
  (request :get (format "/services/data/v%s/sobjects" @+version+) token))

(defn so->all
  "All sobjects i.e (so->all \"Account\" auth-info)"
  [sobject token]
  (request :get (format "/services/data/v%s/sobjects/%s" @+version+ sobject) token))

(defn so->recent
  "The recently created items under an sobject identifier
   e.g (so->recent \"Account\" auth-info)"
  [sobject token]
  (:recentItems (so->all sobject token)))

(defn so->get
  "Fetch a single SObject or passing in a vector of attributes
   return a subset of the data"
  ([sobject identifier fields token]
   (when (or (seq? fields) (vector? fields))
     (let [params (->> (into [] (interpose "," fields))
                       (str/join)
                       (conj ["?fields="])
                       (apply str))
           uri (format "/services/data/v%s/sobjects/%s/%s%s"
                       @+version+ sobject identifier params)
           response (request :get uri token)]
       (dissoc response :attributes))))
  ([sobject identifier token]
   (request :get
            (format "/services/data/v%s/sobjects/%s/%s" @+version+ sobject identifier) token)))

(comment
  ;; Fetch all the info
  (so->get "Account" "001i0000007nAs3" auth)
  ;; Fetch only the name and website attribute
  (so->get "Account" "001i0000007nAs3" ["Name" "Website"] auth))

(defn so->describe
  "Describe an SObject"
  [sobject token]
  (request :get
           (format "/services/data/v%s/sobjects/%s/describe" @+version+ sobject) token))

(comment
  (so->describe "Account" auth))

(defn so->create
  "Create a new record"
  [sobject record token]
  (let [params
        {:form-params record
         :content-type :json}]
    (request :post
             (format "/services/data/v%s/sobjects/%s/" @+version+ sobject) token params)))

(comment
  (so->create "Account" {:Name "My new account"} auth))

(defn so->update
  "Update a record
   - sojbect the name of the object i.e Account
   - identifier the object id
   - record map of data to update object with
   - token your api auth info"
  [sobject identifier record token]
  (let [params
        {:body (json/generate-string record)
         :content-type :json}]
    (request :patch
             (format "/services/data/v%s/sobjects/%s/%s" @+version+ sobject identifier)
             token params)))

(defn so->delete
  "Delete a record
   - sojbect the name of the object i.e Account
   - identifier the object id
   - token your api auth info"
  [sobject identifier token]
  (request :delete
           (format "/services/data/v%s/sobjects/%s/%s" @+version+ sobject identifier)
           token))

(comment
  (so->delete "Account" "001i0000008Ge2OAAS" auth))

(defn so->flow
  "Invoke a flow (see: https://developer.salesforce.com/docs/atlas.en-us.salesforce_vpm_guide.meta/salesforce_vpm_guide/vpm_distribute_system_rest.htm)
  - indentifier of flow (e.g. \"Escalate_to_Case\")
  - inputs map (e.g. {:inputs [{\"CommentCount\" 6
                                \"FeedItemId\" \"0D5D0000000cfMY\"}]})
  - token to your api auth info"
  [identifier token & [data]]
  (let [params {:body (json/generate-string (or data {:inputs []}))
                :content-type :json}]
    (request :post
             (format "/services/data/v%s/actions/custom/flow/%s" @+version+ identifier)
             token params)))

(comment
  (so->flow "Escalate_to_Case" a {:inputs [{"CommentCount" 6
                                            "FeedItemId" "0D5D0000000cfMY"}]}))

;; Salesforce Object Query Language
;; ------------------------------------------------------------------------------------

(defn ^:private gen-query-url
  "Given an SOQL string, i.e \"SELECT name from Account\"
   generate a Salesforce SOQL query url in the form:
   /services/data/v39.0/query?q=SELECT+name+from+Account"
  [version query]
  (let [url  (format "/services/data/v%s/query" version)
        soql (java.net.URLEncoder/encode query "UTF-8")]
    (apply str [url "?q=" soql])))

(defn soql-prepare
  "Prepares params for http client to execute an arbitrary SOQL query
   i.e SELECT name from Account
   http-client-config is an optional map of options accepted by clj-http/core/request, such as keys: connection-timeout connection-request-timeout connection-manager"
  [query token & [http-client-config]]
  (prepare-request :get (gen-query-url @+version+ query) token http-client-config))

(defn soql!
  "Executes an arbitrary SOQL query
   i.e SELECT name from Account
   http-client-config is an optional map of options accepted by clj-http/core/request, such as keys: connection-timeout connection-request-timeout connection-manager
   Returns a map: {:api-result the-result-from-salesforce :limit-info {:used some_number :available some_other_number}}
   "
  [query token & [http-client-config]]
  (-> (soql-prepare query token http-client-config)
      (perform-request)))
