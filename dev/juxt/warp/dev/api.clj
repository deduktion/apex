(ns juxt.warp.dev.api
  (:require
   [integrant.core :as ig]
   [clojure.java.io :as io]
   [juxt.warp.request :refer [handler]]
   [clojure.tools.logging :as log]
   [juxt.warp.yaml :as yaml]))

(defn- nil-doc-exception [document]
  (let [msg (format "No such resource on classpath: %s" document)]
    (ex-info msg {:document document})))

(defmethod ig/init-key ::api
  [_ {:juxt.warp/keys [document]
      :juxt.warp.dev/keys [new-handler-on-each-request?]
      :as options}]

  (if new-handler-on-each-request?
    ;; Returns a handler that does all the work in reconstructing the
    ;; handler before calling it. Intended for dev mode only.
    (fn [req respond raise]
      ;; TODO: Detect we're in prod mode (by checking existence of a
      ;; dev var) and warn if we're in this code path:
      #_(log/warn "Loading document on request. Performance will be adversely impacted.")
      (if-let [doc (io/resource document)]
        (let [h (handler (yaml/parse-string (slurp doc)) options)]
          (h req respond raise))
        (raise (nil-doc-exception document))))

    (let [doc (io/resource document)]
      (log/info "Loading document:" document)
      (when-not doc
        (let [e (nil-doc-exception document)]
          (log/fatal e (format "Fatal error loading OpenAPI document: " document))
          (throw e)))

      (handler (yaml/parse-string (slurp doc)) options))))