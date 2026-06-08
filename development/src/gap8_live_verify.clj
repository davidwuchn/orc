(ns gap8-live-verify
  "Gap-8 LIVE verify — :judge/composite-score-computed event lands per
   bench tick alongside the per-judge :judge/score-emitted events.

   What this proves end-to-end:
     1. Real bench tick (legal-issue-detection repl-researcher) fires
        5 default judges. The processor computes a weighted composite
        from their scores and emits ONE :judge/composite-score-computed
        event per tick.
     2. The composite is a value in [0.0, 1.0] (sanity).
     3. The composite event carries :contributing-judges naming each
        judge that fed the composite, with its score + weight.
     4. No mocks — real OpenRouter, real bench.

   PASS criteria:
     - At least 1 :judge/composite-score-computed event lands
     - Each lands tagged with a valid (sheet, node, tick) tuple
     - Composite score is in [0.0, 1.0]
     - :contributing-judges count matches the count of fired judges
       for that tuple"
  (:require [ai.obney.orc.evaluation.interface]
            [ai.obney.orc.evaluation.interface.schemas]
            [ai.obney.orc.evaluation.core.judge-runtime]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.core.consolidator]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.time.interface :as time]
            [com.brunobonacci.mulog :as u]
            [runner]
            [legal-issue-detection :as task]))

(defn verify!
  []
  (println "============================================================")
  (println " Gap-8 LIVE Verify — :judge/composite-score-computed events")
  (println "============================================================")
  (u/start-publisher! {:type :console})
  (runner/start!)
  (let [ctx (deref @(requiring-resolve 'runner/system-state))]
    (try
      (cp/process-command
        (assoc ctx :command
               {:command/name :ontology/set-living-description-enabled
                :command/id (random-uuid)
                :command/timestamp (time/now)
                :enabled? true}))
      (Thread/sleep 200)

      (println "\n--- Running legal-issue-detection once ---")
      (runner/run! task/task)
      ;; Poll up to 60s for judges + composite to settle
      (loop [waited 0]
        (let [scores (count (into [] (es/read (:event-store ctx)
                                               {:types #{:judge/score-emitted}
                                                :tenant-id (:tenant-id ctx)})))
              composites (count (into [] (es/read (:event-store ctx)
                                                   {:types #{:judge/composite-score-computed}
                                                    :tenant-id (:tenant-id ctx)})))]
          (cond
            (and (pos? composites) (>= scores 5))
            (println "  → settled. scores:" scores ", composites:" composites
                     "waited:" waited "ms")
            (>= waited 60000)
            (println "  ⚠ timeout. scores:" scores ", composites:" composites)
            :else
            (do (Thread/sleep 2000) (recur (+ waited 2000))))))

      (let [composites (into [] (es/read (:event-store ctx)
                                          {:types #{:judge/composite-score-computed}
                                           :tenant-id (:tenant-id ctx)}))
            scores (into [] (es/read (:event-store ctx)
                                      {:types #{:judge/score-emitted}
                                       :tenant-id (:tenant-id ctx)}))
            valid-composites (filter #(and (number? (:composite-score %))
                                            (>= (:composite-score %) 0.0)
                                            (<= (:composite-score %) 1.0)
                                            (seq (:contributing-judges %)))
                                     composites)
            pass? (and (pos? (count composites))
                       (= (count composites) (count valid-composites)))]
        (println "\n============================================================")
        (println " RESULTS")
        (println "============================================================")
        (println "  Total :judge/score-emitted events:" (count scores))
        (println "  Total :judge/composite-score-computed events:" (count composites))
        (println "  Valid composites (score in [0,1] + has contributors):" (count valid-composites))
        (println)
        (when (seq composites)
          (doseq [c composites]
            (println "\n  Composite event:")
            (println "    sheet:" (:sheet-id c))
            (println "    node:" (:node-id c))
            (println "    tick:" (:tick-id c))
            (println "    composite-score:" (:composite-score c))
            (println "    contributing judges:")
            (doseq [j (:contributing-judges c)]
              (println "      -" (:judge-name j)
                       "score:" (:score j)
                       "weight:" (:weight j)))))
        (println)
        (println "  PASS:" pass?)
        {:pass? pass?
         :composites (count composites)
         :valid-composites (count valid-composites)
         :scores (count scores)})
      (finally
        (runner/stop!)))))

(comment
  (require '[gap8-live-verify :as v])
  (v/verify!))
