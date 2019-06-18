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

(defn make-params-for-auth-request
  "Prepare params map for clj_http post call
  app_data is a map in the form
   - client-id ID
   - client-secret SECRET
   - username USERNAME
   - password PASSWORD
   - security-token TOKEN
  http-client-config-map is a (potentially empty) map of options accepted by clj-http/core/request,
  including keys such as: connection-timeout connection-request-timeout connection-manager"
  [{:keys [client-id client-secret username password security-token] :as app_data} http-client-config-map]
  (let [params {:grant_type "password"
                :client_id client-id ; note conversion of hyphen to underscore in key name
                :client_secret client-secret ; note conversion of hyphen to underscore in key name
                :username username
                :password (str password security-token)
                :format "json"}
        all-params (merge {:form-params params} (or http-client-config-map {}))]
    all-params))

(defn auth!
  "Get security token and auth info from Salesforce
   app_data is a map in the form
   - client-id ID
   - client-secret SECRET
   - username USERNAME
   - password PASSWORD
   - security-token TOKEN
   - login-host HOSTNAME (default login.salesforce.com)
   http-client-config-map is an optional map of options accepted by clj-http/core/request, such as keys: connection-timeout connection-request-timeout connection-manager
   "
  [[{:keys [login-host] :as app_data} & [http-client-config-map]]]
  (let [hostname (or login-host "login.salesforce.com")
        auth-url (format "https://%s/services/oauth2/token" hostname)
        all-params (make-params-for-auth-request app_data http-client-config-map)
        resp (http/post auth-url all-params)]
    (-> (:body resp)
        (json/decode true))))

(def ^:private limit-info (atom {}))

(defn- parse-limit-info [v]
  (let [[used available]
        (->> (-> (str/split v #"=")
                 (second)
                 (str/split #"/"))
             (map #(Integer/parseInt %)))]
    {:used used
     :available available}))

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
  "Part 2 of 2 of replacement of 'request' - An impure function that uses output of 'prepare-request to make actual http call via clj-http"
  (let [resp (http/request client-params)]
    (-> (get-in resp [:headers "sforce-limit-info"]) ;; Record limit info in atom
        (parse-limit-info)
        (partial reset! limit-info))
    (-> resp
        :body
        (json/decode true))))

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

(defn-  safe-request
  "Perform a request but catch any exceptions"
  [method url token & params]
  (try
    (with-meta
      (request method url token params)
      {:method method :url url :token token})
    (catch Exception e (.toString e))))

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
   http-client-config-map is an optional map of options accepted by clj-http/core/request, such as keys: connection-timeout connection-request-timeout connection-manager"
  [query token & [http-client-config-map]]
  (prepare-request :get (gen-query-url @+version+ query) token http-client-config-map))

(defn soql!
  "Executes an arbitrary SOQL query
   i.e SELECT name from Account
   http-client-config-map is an optional map of options accepted by clj-http/core/request, such as keys: connection-timeout connection-request-timeout connection-manager"
  [query token & [http-client-config-map]]
  (-> (soql-prepare query token http-client-config-map)
      (perform-request)))
