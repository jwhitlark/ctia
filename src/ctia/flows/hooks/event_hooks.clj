(ns ctia.flows.hooks.event-hooks
  (:require
    [clojure.tools.logging :as log]
    [ctia.events :as events]
    [ctia.flows.hook-protocol :refer [Hook]]
    [ctia.lib.redis :as lr]
    [ctia.events.producers.es.producer :as esp]
    [ctia.properties :refer [properties]]
    [ctia.properties.getters :as pg]
    [schema.core :as s]))

(defrecord ESEventProducer [conn]
  Hook
  (init [_]
    (reset! conn (esp/init-producer-conn)))
  (destroy [_]
    (reset! conn nil))
  (handle [_  event _]
    (try
      (when (some? @conn)
        (esp/handle-produce-event @conn event))
      (catch Exception e
        ;; Should we really be swallowing exceptions?
        (log/error "Exception while producing event" e)))
    event))

(defn es-event-producer []
  (->ESEventProducer (atom {})))

(defrecord RedisEventPublisher [conn publish-channel-name]
  Hook
  (init [_]
    :nothing)
  (destroy [_]
    :nothing)
  (handle [_  event _]
    (lr/publish conn
                publish-channel-name
                event)
    event))

(defn redis-event-publisher []
  (let [{:keys [channel-name timeout-ms] :as redis-config} (get-in @properties [:ctia :hook :redis])
        [host port] (pg/parse-host-port redis-config)]
    (->RedisEventPublisher (lr/server-connection host port timeout-ms)
                           channel-name)))

(defrecord ChannelEventPublisher []
  Hook
  (init [_]
    (assert (some? @events/central-channel)
            "Events central-channel was not setup"))
  (destroy [_]
    :nothing)
  (handle [_ event _]
    (events/send-event event)
    event))

(s/defn register-hooks :- {s/Keyword [(s/protocol Hook)]}
  [hooks-m :- {s/Keyword [(s/protocol Hook)]}]
  (let [{{redis-enabled? :enabled} :redis
         {es-enabled? :enabled} :es}
        (get-in @properties [:ctia :hook])]
    (cond-> hooks-m
      redis-enabled? (update :event #(conj % (redis-event-publisher)))
      es-enabled?    (update :event #(conj % (es-event-producer)))
      :always        (update :event #(conj % (->ChannelEventPublisher))))))
