(ns turvata.settings-test
  (:require
   [clojure.test :refer [deftest is use-fixtures]]
   [turvata.runtime :as rt]
   [turvata.test-support :as ts]))

(use-fixtures :each
  (fn [f]
    (binding [rt/*runtime* (rt/make-runtime {:settings ts/test-settings})]
      (f))))

(def http-req  {:scheme :http :headers {}})
(def https-req {:scheme :https :headers {}})

(deftest cookie-attrs-http-vs-https
  (is (false? (:secure (rt/cookie-attrs http-req))))
  (is (true?  (:secure (rt/cookie-attrs https-req)))))

(deftest proxy-headers-detected
  (let [req {:scheme :http
             :headers {"x-forwarded-proto" "https"}}]
    (is (true? (:secure (rt/cookie-attrs req))))))
