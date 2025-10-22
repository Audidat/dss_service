(ns com.audidat.dss-service.core
  (:require [org.httpkit.server :as http]
            [reitit.ring :as ring]
            [com.audidat.dss-service.handler :as handler])
  (:gen-class))

(def app
  (ring/ring-handler
   (ring/router
    [["/health" {:get (fn [_] {:status 200 :body "ok"})}]
     ["/hello/:name"
      {:get (fn [{{:keys [name]} :path-params}]
              {:status 200
               :headers {"content-type" "application/json"}
               :body (str "{\"msg\":\"hello " name "\"}")})}]
     ["/api/sign"
      {:post handler/sign-pdf-handler}]
     ["/api/extend"
      {:post handler/extend-pdf-handler}]])
   (ring/create-default-handler)))

(defonce server (atom nil))
(defn -main [& _]
  (let [config (handler/get-config)]
    (println "=== DSS Service Starting ===")
    (println "Configuration:")
    (println "  P12 Path:" (:p12-path config))
    (println "  TSA URL:" (:tsa-url config))
    (println "Endpoints:")
    (println "  GET  /health")
    (println "  POST /api/sign - Sign PDF with PAdES-BASELINE-LTA")
    (println "  POST /api/extend - Extend signed PDF to PAdES-BASELINE-LTA")
    (reset! server (http/run-server app {:port 4000 :thread 8 :queue-size 2048}))
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. #(when-let [s @server] (s :timeout 2000))))
    (println "Server started on port 4000")))
