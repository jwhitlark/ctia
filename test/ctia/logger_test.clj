(ns ctia.logger-test
  (:require [ctia.test-helpers
             [core :as test-helpers]
             [es :as es-helpers]]
            [ctia.entity.event.obj-to-event :as o2e]
            [ctia.logging-core :refer [logging-prefix]]
            [clojure.test :refer [deftest is use-fixtures]]
            [schema.test :as st]
            [clojure.tools.logging :as log]
            [clojure.tools.reader.edn :as edn]
            [clojure.tools.reader.reader-types :as rdr-types :refer [string-push-back-reader]]
            [clojure.string :as str])
  (:import [java.io PushbackReader]))

(use-fixtures :each
  es-helpers/fixture-properties:es-store
  test-helpers/fixture-properties:events-logging
  test-helpers/fixture-ctia-fast
  st/validate-schemas)

(deftest test-logged
  (let [app (test-helpers/get-current-app)
        {:keys [send-event]} (test-helpers/get-service-map app :EventsService)
        sb (StringBuilder.)
        patched-log (fn [_logger
                         _level
                         _throwable
                         message]
                      (.append sb message)
                      (.append sb "\n"))]
    (with-redefs [log/log* patched-log]
      (send-event (o2e/to-create-event
                     {:owner "tester"
                      :groups ["foo"]
                      :id "test-1"
                      :type :test
                      :tlp "green"
                      :data 1}
                     "test-1"
                     "tester"))
      (send-event (o2e/to-create-event
                     {:owner "tester"
                      :groups ["foo"]
                      :id "test-2"
                      :type :test
                      :tlp "green"
                      :data 2}
                     "test-2"
                     "tester"))
      (Thread/sleep 100)   ;; wait until the go loop is done
      ;; we're looking for two events of the form "event: <EDN>"
      (let [pbr->str (fn [pbr]
                       (loop [^StringBuilder sb (StringBuilder.)]
                         (let [c (rdr-types/read-char pbr)]
                           (if (nil? c)
                             (str sb)
                             (recur (.append sb c))))))
            events (loop [^String s (str sb)
                          events []]
                     (let [;; advance to the next event prefix
                           next-prefix-start (.indexOf s logging-prefix)]
                       (if (= -1 next-prefix-start)
                         events
                         (let [[event s] (with-open [rdr (string-push-back-reader
                                                           ;; scroll past event prefix
                                                           (subs s (+ next-prefix-start (count logging-prefix))))]
                                           (let [event (edn/read-string)]
                                             [event (pbr->str rdr)]))]
                           (recur s (conj events event))))))
            ;; sb "Lifecycle worker completed :boot lifecycle task; awaiting next task.event: {:owner tester, :groups [foo], :entity {:owner tester, :groups [foo], :id test-1, :type :test, :tlp green, :data 1}, :timestamp #inst \"\", :id test-1, :type event, :tlp green, :event_type :record-created}\nevent: {:owner tester, :groups [foo], :entity {:owner tester, :groups [foo], :id test-2, :type :test, :tlp green, :data 2}, :timestamp #inst \"\", :id test-2, :type event, :tlp green, :event_type :record-created}\n\n"
            ;;scrubbed (-> (str sb)
            ;;             (str/replace #"#inst \"[^\"]*\"" "#inst \"\"")
            ;;             (str/replace #":id event[^,]*" ":id event")
            ;;             (str/replace #"Lifecycle worker completed :boot lifecycle task; awaiting next task.\s*"
            ;;                          ""))
            ;expected
            ;"event: {:owner tester, :groups [foo], :entity {:owner tester, :groups [foo], :id test-1, :type :test, :tlp green, :data 1}, :timestamp #inst \"\", :id test-1, :type event, :tlp green, :event_type :record-created}\nevent: {:owner tester, :groups [foo], :entity {:owner tester, :groups [foo], :id test-2, :type :test, :tlp green, :data 2}, :timestamp #inst \"\", :id test-2, :type event, :tlp green, :event_type :record-created}\n"
            ]
        (is (= nil events))))))
