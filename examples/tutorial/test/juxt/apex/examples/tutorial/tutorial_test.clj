;; Copyright © 2020, JUXT LTD.

(ns juxt.apex.examples.tutorial.tutorial-test
  (:require
   [clojure.test :refer [deftest is]]
   [juxt.apex.alpha.http.handler :as handler]
   [juxt.apex.alpha.http.core :as http]
   [juxt.reap.alpha.decoders :as reap]
   [juxt.apex.examples.tutorial.util :refer [pick-variants]]))

;; TODO: Make a better response equality check better for testing, possibly
;; exploiting clojure.test/assert-predicate
(defn wrap-remove-header [h header]
  (fn [req]
    (->
     (h req)
     (update :headers dissoc header))))

;; Resource location

(deftest locate-resource-test
  (let [provider
        (reify
          http/ResourceLocator
          (locate-resource [_ uri]
            (when (= (.getPath uri) "/hello.txt")
              {:apex.http/content "Hello World!"}))
          http/ResponseBody
          (send-ok-response
              [_ resource response request respond raise]
              (respond
               (conj response [:body (:apex.http/content resource)]))))
        h (-> (handler/handler provider)
              (wrap-remove-header "date"))]
    (is (=
         {:status 200
          :headers {}
          :body "Hello World!"}
         (h {:request-method :get
             :uri "/hello.txt"
             :scheme :https})))

    (is (=
         {:status 404
          :headers {}}
         (h {:request-method :get
             :uri "/not-exists"
             :scheme :https})))))

(deftest response-body-test
  (is
   (=
    "Hello World!"
    (let [provider
          (reify
            http/ResourceLocator
            (locate-resource [this uri] {:juxt.http/content "Hello World!"})
            http/ResponseBody
            (send-ok-response [this resource response request respond raise]
              (respond
               (conj
                response
                [:body (:juxt.http/content resource)]))))
          h (handler/handler provider)]
      (:body
       (h {:request-method :get
           :uri "/"
           :scheme :https}))))))

;; Content negotiation

(deftest content-negotiation-test
  (let [provider
        (reify
          http/ResourceLocator
          (locate-resource [_ uri]
            (->
             {"/hello"
              {:juxt.http/variant-locations
               [(java.net.URI. "/hello.html")
                (java.net.URI. "/hello.txt")]}
              "/hello.html"
              {:juxt.http/content "<h1>Hello World!</h1>"
               :juxt.http/content-type
               (reap/content-type "text/html;charset=utf-8")}
              "/hello.txt"
              {:juxt.http/content "Hello World!"
               :juxt.http/content-type
               (reap/content-type "text/plain;charset=utf-8")}}
             (get (. uri getPath))))

          http/ContentNegotiation
          (best-representation [provider resource request]
            (pick-variants provider resource request))

          http/ResponseBody
          (send-ok-response
              [_ resource response request respond raise]
              (respond
               (conj response [:body (:juxt.http/content resource)]))))
        h (-> (handler/handler provider)
              (wrap-remove-header "date"))]

    (is (=
         {:status 200
          :headers {"content-location" "/hello.html"}
          :body "<h1>Hello World!</h1>"}
         (h {:request-method :get
             :scheme :https
             :uri "/hello"
             :headers {"accept" "text/html"}})))))

;; Conditional requests

(deftest conditional-request-with-last-modified-test
  (let [provider
        (reify
          http/ResourceLocator
          (locate-resource [this uri]
            {:juxt.http/content "Hello World!"
             :juxt.http/last-modified (http/decode-date "Wed, 8 Jul 2020 22:00:00 GMT")})

          http/LastModified
          (last-modified [_ representation]
            (:juxt.http/last-modified representation))

          http/ResponseBody
          (send-ok-response [this resource response request respond raise]
            (respond
             (conj
              response
              [:body (:juxt.http/content resource)]))))

        h (handler/handler provider)

        response (h {:scheme :https
                           :uri "/"
                           :request-method :get})]

    (is (= 200 (:status response)))

    (let [last-modified (get-in response [:headers "last-modified"])

          response (h {:scheme :https
                              :uri "/"
                              :request-method :get
                              :headers {"if-modified-since" last-modified}})]

      (is (= 304 (:status response)))

      (let [last-modified
          (-> last-modified http/decode-date (.minusSeconds 2) http/encode-date)

          response (h {:scheme :https
                             :uri "/"
                             :request-method :get
                             :headers {"if-modified-since" last-modified}})]

        (is (= 200 (:status response)))))))
