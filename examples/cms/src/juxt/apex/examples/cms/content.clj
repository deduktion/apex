(ns juxt.apex.examples.cms.content
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

(def WEBSITE_REPO_DIR (io/file (System/getProperty "user.home") "src/github.com/juxt/website"))

(defn slurp-file-as-b64encoded-string [f]
  (try
    (let [bytes (.readAllBytes (new java.io.FileInputStream f))]
      {:apex/content (.encodeToString (java.util.Base64/getEncoder) bytes)
       :apex/content-length (count bytes)
       :apex/content-coding :base64
       :apex/last-modified (java.util.Date. (.lastModified f))
       :apex/content-source (.toURI f)})
    (catch Throwable t
      (throw (ex-info "Failed to load file" {:file f} t)))))

(defn slurp-file-as-string [f]
  (try
    {:apex/content (slurp f)
     :apex/content-length (.length f)
     :apex/last-modified (java.util.Date. (.lastModified f))
     :apex/content-source (.toURI f)}
    (catch Throwable t
      (throw (ex-info "Failed to load file" {:file f} t)))))

(defn ingest-content [tx]
  (cond-> tx
    (and
     (not (:apex/content tx))
     (:apex/content-source tx))
    (merge
     (let [f (io/file (:apex/content-source tx))]
       (case (:apex/content-coding tx)
         :base64
         (slurp-file-as-b64encoded-string f)
         (slurp-file-as-string f))))))

(defn compute-content-length
  "Where no content-length already exists, add it."
  [tx]
  (cond-> tx
    (and
     (not (:apex/content-length tx))
     (:apex/content tx)
     (not (:apex/content-coding tx)))
    (assoc :apex/content-length (.length (:apex/content tx)))))

(defn compute-etag [tx]
  ;; If there _is_ content,
  (cond-> tx
    (and
     (not (:apex/entity-tag tx))         ; no pre-existing entity-tag
     (:apex/content tx)             ; but some content
     )
    (assoc
     :apex/entity-tag
     (hash
      (select-keys
       tx
       [:apex/content ; if the content changed, the etag would too
        :apex/content-encoding
        :apex/content-language
        :apex/content-type])))))

(defn content-txes []
  (map compute-etag
       (remove nil?
               (concat

                ;; Content
                (->>
                 (map
                  (comp compute-content-length ingest-content)
                  (edn/read-string
                   {:readers {'crux/uri (fn [x] (java.net.URI. x))
                              'crux.cms/file (fn [path]
                                               (.getAbsolutePath (io/file WEBSITE_REPO_DIR path)))}}
                   (slurp "src/juxt/apex/examples/cms/content.edn"))))

                ;; Selmer template sources
                (let [dir (io/file WEBSITE_REPO_DIR "juxt.website/resources/templates")]
                  (for [f (file-seq dir)
                        :when (.isFile f)
                        :let [p (str (.relativize (.toPath dir) (.toPath f)))]]
                    (merge
                     {:crux.db/id (java.net.URI. (str "https://juxt.pro/_sources/templates/" p))
                      :apex/content-type "text/plain;charset=utf-8"
                      :apex/content-language "en"}
                     (slurp-file-as-string f))))))))
