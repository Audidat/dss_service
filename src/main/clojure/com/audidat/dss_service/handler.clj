(ns com.audidat.dss-service.handler
  (:require [com.audidat.dss-service.signer :as signer])
  (:import (java.io ByteArrayOutputStream)))

(defn get-config
  "Get configuration from environment variables with defaults."
  []
  {:p12-path (or (System/getenv "P12_PATH") "cotelmur.p12")
   :p12-password (or (System/getenv "P12_PASSWORD") "REMOVED")
   :tsa-url (or (System/getenv "TSA_URL") "http://timestamp.digicert.com")})

(defn read-body-bytes
  "Reads the entire request body into a byte array."
  [request]
  (let [body (:body request)]
    (if body
      (with-open [baos (ByteArrayOutputStream.)]
        (let [buffer (byte-array 8192)]
          (loop []
            (let [n (.read body buffer)]
              (when (pos? n)
                (.write baos buffer 0 n)
                (recur))))
          (.toByteArray baos)))
      (throw (IllegalArgumentException. "Request body is empty")))))

(defn sign-pdf-handler
  "Handler for POST /api/sign endpoint.
   Accepts raw PDF bytes in request body and returns signed PDF."
  [request]
  (try
    (let [config (get-config)
          pdf-bytes (read-body-bytes request)
          signed-pdf (signer/sign-pdf-bytes
                      :pdf-bytes pdf-bytes
                      :p12-path (:p12-path config)
                      :p12-password (:p12-password config)
                      :tsa-url (:tsa-url config))]
      {:status 200
       :headers {"content-type" "application/pdf"
                 "content-disposition" "attachment; filename=\"signed.pdf\""}
       :body (java.io.ByteArrayInputStream. signed-pdf)})
    (catch IllegalArgumentException e
      {:status 400
       :headers {"content-type" "application/json"}
       :body (str "{\"error\":\"" (.getMessage e) "\"}")})
    (catch Exception e
      (println "Error signing PDF:" (.getMessage e))
      (.printStackTrace e)
      {:status 500
       :headers {"content-type" "application/json"}
       :body (str "{\"error\":\"Internal server error: " (.getMessage e) "\"}")})))

(defn extend-pdf-handler
  "Handler for POST /api/extend endpoint.
   Accepts a signed PDF (PAdES-BT or any level) in request body and extends it to LTA."
  [request]
  (try
    (let [config (get-config)
          pdf-bytes (read-body-bytes request)
          extended-pdf (signer/extend-pdf-bytes
                        :pdf-bytes pdf-bytes
                        :p12-path (:p12-path config)
                        :p12-password (:p12-password config)
                        :tsa-url (:tsa-url config))]
      {:status 200
       :headers {"content-type" "application/pdf"
                 "content-disposition" "attachment; filename=\"extended_lta.pdf\""}
       :body (java.io.ByteArrayInputStream. extended-pdf)})
    (catch IllegalArgumentException e
      {:status 400
       :headers {"content-type" "application/json"}
       :body (str "{\"error\":\"" (.getMessage e) "\"}")})
    (catch Exception e
      (println "Error extending PDF to LTA:" (.getMessage e))
      (.printStackTrace e)
      {:status 500
       :headers {"content-type" "application/json"}
       :body (str "{\"error\":\"Internal server error: " (.getMessage e) "\"}")})))
