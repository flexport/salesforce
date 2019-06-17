(defproject org.clojars.flexport-clojure-eng/salesforce "1.0.5.7"
  :description "A clojure library for accessing the salesforce api"
  :url "https://www.flexport.com"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [clj-http "3.10.0"]
                 [cheshire "5.8.1"]
                 [org.clojure/tools.logging "0.4.1"]]

  :plugins [[lein-cljfmt "0.6.4"]
            [lein-ancient "0.6.15"]]) ; For detecting and fixing outdated dependencies. See https://github.com/xsc/lein-ancient
