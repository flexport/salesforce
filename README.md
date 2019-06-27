# Under development
This library forked from [owainlewis/salesforce](https://github.com/owainlewis/salesforce) is under development and 
should be considered as pre-release. While this development continues, all versions will contain the `SNAPSHOT` 
qualifier.
  
Salesforce API calls are being refactored to work in 2 steps:
  1. Prepare a map of params
  2. Send the map of params to clj-http's `request` function
  
Because the preparation functions are pure, they are easy to test. We don't need to test clj-http's `request` 
function; we only need to test that we are sending it what we expect to be sending it.

Another new feature is that the preparation functions expose the ability to specify http client configurations such as 
timeouts.

While this development is in progress, functions that have not been migrated to the new approach have been commented
out. 

The migrated functions have a new response shape; instead of just returning the api results, they return a map that 
includes both the api results, under key `:api-results`, and the limit info as a possibly empty map under key `:limit-info`.
In addition, the keys of the map under `:api-results` are always converted to keywords. This may become an option in 
future versions. 


# Salesforce

More information about the Salesforce REST API can be found at

[http://www.salesforce.com/us/developer/docs/api_rest/](http://www.salesforce.com/us/developer/docs/api_rest/)

## How do I use it?

It is available from clojars.org.

```
[org.clojars.flexport-clojure-eng/salesforce "2.0.0-SNAPSHOT"]
```

## Usage

We first need to set up some authentication information as a Clojure map. All the information can be found in your Salesforce account.

In order to get an auth token and information about your account we call the auth! function
like this

```clojure
(use 'salesforce.core)

(def config
  {:client-id ""
   :client-secret ""
   :username ""
   :password ""
   :security-token ""})

(def auth-info (:api-result (auth! config)))
```
You can optionally pass in :login-host if you want to use test.salesforce.com or my.salesforce.com addresses

This returns a map of information about your account including an authorization token that will allow you to make requests to the REST API.

The response looks something like this

```clojure
{:id "https://login.salesforce.com/id/1234",
 :issued_at "1367488271359",
 :instance_url "https://na15.salesforce.com",
 :signature "1234",
 :access_token "1234"}
```

Now you can use your auth-config to make requests to the API.

## Setting the API version

There are multiple versions of the Salesforce API so you need to decare the version you want to use.

You can easily get the latest API version with the following function

```clojure
(latest-version) ;; => "46.0"
```

You can set a version in several ways.

Globally

```clojure
(set-version! "46.0")
```

Inside a macro

```clojure
(with-version "46.0"
  ;; Do stuff here )

```

Or just using the latest version (this is slow as it needs to make an additional http request)

```clojure
(with-latest-version
  ;; Do stuff here)
```

## Salesforce Object Query Language

Salesforce provides a query language called SOQL that lets you run custom queries on the API.

```clojure
(soql "SELECT name from Account" auth-info)
```

## A sample session

This final example shows an example REPL session using the API

```clojure

(def config
  {:client-id ""
   :client-secret ""
   :username ""
   :password ""
   :security-token ""})

;; Get auth info needed to make http requests
(def auth (:api-result (auth! config)))

;; Get and then set the latest API version globally
(set-version! (latest-version))

;; Now we are all set to access the salesforce API

;; Use SOQL to find account information
(:records (soql "SELECT name from Account" auth))

```

## Contributors

+ Owain Lewis
+ Rod Pugh

## License

Distributed under the Eclipse Public License, the same as Clojure.
