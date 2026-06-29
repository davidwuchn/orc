(ns ai.obney.orc.ontology.el5-domain-penalty-test
  "EL-5 (ADR 0016, emergence loop): the deterministic CONTRASTIVE domain penalty
   + the PLUGGABLE scorer seam (ADR 0016 amendment).

   Deterministic surface under test — the CONTRAST is PURE arithmetic and the
   scorer is an INJECTED capability (faked here; the live four-case verify with
   real ColBERT + real grain is the rate probe / harness):

     1. domain-penalty: clamp / margin / cap edges + the contrastive gate
        (avoid>good fires; good>avoid => 0; within-margin => 0).
     2. apply-penalty: final = fitness * (1 - penalty); nil passes through.
     3. THE SCORER SEAM: penalize-candidates with an INJECTED FAKE scorer
        returning fixed cos-avoid/cos-good — a penalized shape-winner drops BELOW
        an unpenalized correct candidate (the re-sort). No ColBERT, no LLM, no
        DJL.
     4. THE COLBERT ADAPTER: colbert-rerank-scores maps a stubbed colbert/rerank
        output -> max-normalized cos-avoid/cos-good (split-by-content + MAX +
        normalize). rerank-fn is stubbed -> assert the mapping, NOT ColBERT's
        actual scores (that is the live verify).
     5. CONFIG SELECTION: make-scorer picks :colbert vs :embedding by config.
     6. score-candidate: contrast on real-shaped candidates with a fake scorer.

   No real backend here by design — the contrast + adapter mapping are
   reproducible run-to-run; this is the discipline 'TDD the deterministic
   penalty math + seam'. The live four-case verify is the rate probe / harness."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string]
            [ai.obney.orc.ontology.core.domain-penalty :as dp]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.embedding :as embedding]
            [ai.obney.orc.ontology.core.reranker :as reranker]
            [ai.obney.orc.colbert.interface :as colbert]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-v3.interface.schemas]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]))

(defn- approx?
  "Float tolerance — the contrast is exact arithmetic but IEEE-754 subtraction
   (e.g. 0.65 - 0.60 - 0.05) leaves sub-femto residue; assert to 1e-9."
  ([a b] (approx? a b 1e-9))
  ([a b eps] (< (Math/abs (- (double a) (double b))) eps)))

(def ^:private cfg
  "Explicit config so the tests don't drift if the conservative defaults are
   re-tuned. scale 2.0, margin 0.05, cap 0.6 — independent of the production
   defaults; the math is what is under test, not the calibrated knob values."
  {:penalty-scale 2.0 :margin 0.05 :penalty-cap 0.6})

;; A fake scorer: returns fixed {:cos-avoid :cos-good} per candidate by reading
;; the candidate's own pre-stamped values. This is the SEAM — the penalty pass
;; is pure given the scorer, so we never touch ColBERT/embeddings.
(defn- fixed-scorer
  "Scorer fn that reads the candidate's ::cos-avoid / ::cos-good fields directly,
   so a test states the exact contrast cosines and asserts the penalty + re-sort."
  [candidate _task]
  {:cos-avoid (double (or (::cos-avoid candidate) 0.0))
   :cos-good  (double (or (::cos-good candidate) 0.0))})

;; =============================================================================
;; 1. domain-penalty — the contrastive gate + clamp/margin/cap edges
;; =============================================================================

(deftest domain-penalty-fires-only-when-avoid-beats-good-by-more-than-margin
  (testing "avoid > good beyond margin => penalty fires (> 0)"
    ;; contrast = 0.8 - 0.4 - 0.05 = 0.35 ; penalty = 2.0 * 0.35 = 0.70 -> capped 0.6
    (is (= 0.6 (dp/domain-penalty 0.8 0.4 cfg))))

  (testing "good > avoid => penalty 0 (the use-case wins => never penalized)"
    (is (= 0.0 (dp/domain-penalty 0.3 0.7 cfg)))
    (is (= 0.0 (dp/domain-penalty 0.5 0.5 cfg))))

  (testing "avoid beats good but WITHIN margin => penalty ~0 (noise floor)"
    ;; contrast raw = 0.62 - 0.60 = 0.02 < margin 0.05 => 0
    (is (= 0.0 (dp/domain-penalty 0.62 0.60 cfg)))
    ;; exactly at margin => ~0 (max(0, ~0); IEEE residue is sub-femto)
    (is (approx? 0.0 (dp/domain-penalty 0.65 0.60 cfg))))

  (testing "just past margin => a small graded penalty (not capped)"
    ;; contrast = 0.70 - 0.60 - 0.05 = 0.05 ; penalty = 2.0 * 0.05 = 0.10
    (is (approx? 0.1 (dp/domain-penalty 0.70 0.60 cfg)))))

(deftest domain-penalty-clamps-to-cap-and-zero
  (testing "huge contrast is clamped to penalty-cap (never annihilates)"
    (is (= 0.6 (dp/domain-penalty 1.0 0.0 cfg))))
  (testing "penalty is never negative"
    (is (= 0.0 (dp/domain-penalty 0.0 1.0 cfg))))
  (testing "cap is honoured even at scale 10"
    (is (= 0.6 (dp/domain-penalty 0.9 0.1 (assoc cfg :penalty-scale 10.0))))))

;; =============================================================================
;; 2. apply-penalty — final = fitness * (1 - penalty)
;; =============================================================================

(deftest apply-penalty-multiplies-and-passes-nil
  (testing "fitness multiplied by (1 - penalty): 0.95 * (1 - 0.6) = 0.38"
    (is (approx? 0.38 (dp/apply-penalty 0.95 0.6))))
  (testing "penalty 0 leaves fitness untouched"
    (is (approx? 0.85 (dp/apply-penalty 0.85 0.0))))
  (testing "nil fitness (colbert-fallback) passes through nil — no fabricated score"
    (is (nil? (dp/apply-penalty nil 0.5)))))

;; =============================================================================
;; 3. THE SCORER SEAM — penalize-candidates RE-SORT with an INJECTED fake scorer
;;    (no ColBERT, no LLM, no DJL — the penalty pass is pure given the scorer)
;; =============================================================================

(deftest penalize-candidates-demotes-shape-winner-below-correct
  (testing "a penalized shape-winner (higher LLM fitness) drops BELOW an unpenalized correct candidate"
    (let [candidates [;; A: shape winner — avoid-condition fits the task (cos-avoid HIGH,
                      ;; cos-good LOW) => penalized.
                      {:document-id "A" :fitness-score 0.95
                       ::dp/cos-avoid 1.0 ::dp/cos-good 0.0}
                      ;; B: correct — use-case fits its own task (cos-good HIGH,
                      ;; cos-avoid LOW) => penalty 0, untouched.
                      {:document-id "B" :fitness-score 0.80
                       ::dp/cos-avoid 0.0 ::dp/cos-good 1.0}]
          ;; rename ::cos-* to the keys fixed-scorer reads (ns-qualified above)
          scorer (fn [c _t] {:cos-avoid (double (or (::dp/cos-avoid c) 0.0))
                             :cos-good  (double (or (::dp/cos-good c) 0.0))})
          out (dp/penalize-candidates nil candidates "the refactor extract task" cfg scorer)
          a (first (filter #(= "A" (:document-id %)) out))
          b (first (filter #(= "B" (:document-id %)) out))]
      ;; A: cos-avoid 1.0, cos-good 0.0 => penalty capped 0.6 => fitness 0.95*0.4=0.38
      (is (> (:domain-penalty a) 0.0) "A (shape winner whose avoid-condition matches the task) is penalized")
      ;; B: cos-avoid 0.0, cos-good 1.0 => penalty 0 => fitness untouched 0.80
      (is (= 0.0 (:domain-penalty b)) "B (correct, use-case matches its own task) is NOT penalized")
      (is (< (:fitness-score a) (:fitness-score b))
          "penalized A's fitness drops below unpenalized B's")
      (is (= "B" (:document-id (first out)))
          "RE-SORT: B (correct) is now #1, ahead of the demoted A"))))

(deftest penalize-candidates-keeps-nil-fitness-last
  (testing "a :colbert-fallback candidate (nil fitness) survives and sorts last"
    (let [candidates [{:document-id "good" :fitness-score 0.7 ::dp/cos-avoid 0.0 ::dp/cos-good 0.0}
                      {:document-id "fb" :fitness-score nil ::dp/cos-avoid 0.0 ::dp/cos-good 0.0}]
          scorer (fn [c _t] {:cos-avoid (double (or (::dp/cos-avoid c) 0.0))
                             :cos-good  (double (or (::dp/cos-good c) 0.0))})
          out (dp/penalize-candidates nil candidates "t" cfg scorer)]
      (is (= "good" (:document-id (first out))))
      (is (nil? (:fitness-score (last out)))))))

;; =============================================================================
;; 4. THE COLBERT ADAPTER — colbert-rerank-scores maps a STUBBED colbert/rerank
;;    output -> max-normalized cos-avoid/cos-good (split-by-content + MAX +
;;    normalize). The actual ColBERT scores are NOT tested here — the live verify.
;; =============================================================================

(deftest colbert-adapter-splits-by-content-maxes-and-normalizes
  (testing "one rerank call over (concat avoid good); split BY CONTENT, MAX per group, normalize each"
    (let [avoid ["AVOID-1" "AVOID-2"]
          good  ["GOOD-1"]
          ;; Stub rerank: raw ColBERT scores per content. AVOID-2 is the max
          ;; avoid (20.0); GOOD-1 is 10.0. With /40 linear norm => 0.5 vs 0.25.
          captured (atom nil)
          rerank-fn (fn [opts]
                      (reset! captured opts)
                      [{:content "AVOID-1" :score 8.0}
                       {:content "AVOID-2" :score 20.0}
                       {:content "GOOD-1"  :score 10.0}])
          norm-fn (fn [score] (min 1.0 (max 0.0 (/ (double score) 40.0))))
          {:keys [cos-avoid cos-good]}
          (dp/colbert-rerank-scores rerank-fn norm-fn avoid good "the task")]
      (is (= "the task" (:query @captured)) "the task is the single rerank query")
      (is (= ["AVOID-1" "AVOID-2" "GOOD-1"] (:documents @captured))
          "ONE call: (concat avoid good) deduped, in order")
      (is (approx? 0.5 cos-avoid) "cos-avoid = MAX over avoid (20.0) normalized /40")
      (is (approx? 0.25 cos-good) "cos-good = MAX over good (10.0) normalized /40"))))

(deftest colbert-adapter-no-guards-is-zero-not-fabricated
  (testing "no avoid guards => cos-avoid 0.0 (a candidate with no :avoid-when can't be penalized)"
    (let [rerank-fn (fn [_] [{:content "GOOD-1" :score 30.0}])
          norm-fn (fn [score] (/ (double score) 40.0))
          {:keys [cos-avoid cos-good]}
          (dp/colbert-rerank-scores rerank-fn norm-fn [] ["GOOD-1"] "task")]
      (is (= 0.0 cos-avoid) "no avoid guards => 0.0, never fabricated")
      (is (approx? 0.75 cos-good))))
  (testing "no guards at all => {:cos-avoid 0 :cos-good 0}, rerank never called"
    (let [called (atom false)
          rerank-fn (fn [_] (reset! called true) [])
          {:keys [cos-avoid cos-good]}
          (dp/colbert-rerank-scores rerank-fn identity [] [] "task")]
      (is (= 0.0 cos-avoid))
      (is (= 0.0 cos-good))
      (is (false? @called) "empty doc set => no bridge round-trip"))))

(deftest colbert-scorer-builds-avoid-good-from-candidate-signals
  (testing "colbert-scorer reads avoid-strings/positive-strings off the candidate, scores via the stub"
    (let [cand {:avoid-when ["the task is an extract/refactor, not a pure rename"]
                :content "behavior-preserving pure rename of a symbol"
                :strengths [{:good-when "a pure rename across files"}]}
          ;; Stub rerank: the avoid guard scores higher than both positive strings
          ;; for this (refactor) task — i.e. the refactor force-fit shape.
          rerank-fn (fn [_]
                      [{:content "the task is an extract/refactor, not a pure rename" :score 20.0}
                       {:content "behavior-preserving pure rename of a symbol" :score 12.0}
                       {:content "a pure rename across files" :score 10.0}])
          scorer (dp/colbert-scorer nil dp/default-penalty-config rerank-fn
                                    (fn [score & {:keys [max-score]
                                                  :or {max-score 40.0}}]
                                      (/ (double score) max-score)))
          {:keys [cos-avoid cos-good]} (scorer cand "refactor extract a helper")]
      (is (approx? 0.5 cos-avoid) "avoid guard 20.0/40 = 0.5")
      ;; cos-good = MAX over (:content, :good-when) = MAX(12,10)/40 = 0.3
      (is (approx? 0.3 cos-good) "cos-good = MAX over summary + good-when, normalized")
      (is (> cos-avoid cos-good) "the refactor force-fit shape: avoid beats good"))))

;; =============================================================================
;; 5. CONFIG SELECTION — make-scorer picks :colbert vs :embedding by config
;; =============================================================================

(deftest make-scorer-selects-backend-by-config
  (testing ":embedding config builds the embedding scorer (uses embed+cosine; faked here)"
    ;; A fake embed-fn so no DJL loads; the embedding scorer must call it.
    (let [embed-calls (atom [])
          fake-embed (fn ([s] (swap! embed-calls conj s) [1.0 0.0])
                       ([s _] (swap! embed-calls conj s) [1.0 0.0]))
          scorer (dp/embedding-scorer {:scorer :embedding} fake-embed)
          cand {:avoid-when ["av"] :content "go"}
          res (scorer cand "the task")]
      (is (contains? res :cos-avoid))
      (is (contains? res :cos-good))
      (is (some #{"the task"} @embed-calls) "the embedding scorer embeds the task (real backend, faked)")
      (is (some #{"av"} @embed-calls) "and embeds the avoid guard")))

  (testing ":colbert is the DEFAULT scorer keyword"
    (is (= :colbert (:scorer dp/default-penalty-config))))

  (testing "make-scorer with :embedding returns a working embedding scorer (no ColBERT bridge)"
    ;; with-redefs the real embed-text to a fake so make-scorer's :embedding
    ;; branch never loads DJL; assert it produces scores.
    (with-redefs [embedding/embed-text (fn ([_] [1.0 0.0]) ([_ _] [1.0 0.0]))]
      (let [scorer (dp/make-scorer nil {:scorer :embedding})
            res (scorer {:avoid-when ["a"] :content "b"} "task")]
        (is (map? res))
        (is (contains? res :cos-avoid)))))

  (testing "make-scorer with :colbert routes through colbert/rerank (stubbed -> no bridge)"
    (with-redefs [colbert/rerank (fn [_ _] [{:content "a" :score 20.0}
                                            {:content "b" :score 10.0}])]
      (let [scorer (dp/make-scorer nil {:scorer :colbert})
            res (scorer {:avoid-when ["a"] :content "b"} "task")]
        (is (map? res))
        ;; avoid "a" = 20/40 = 0.5 ; good "b" = 10/40 = 0.25
        (is (approx? 0.5 (:cos-avoid res)))
        (is (approx? 0.25 (:cos-good res)))))))

;; =============================================================================
;; 6. score-candidate — CONTRASTIVE on real-shaped candidates with a fake scorer
;; =============================================================================

(deftest score-candidate-zero-false-positive-on-own-domain
  (testing "case (3) shape: web-search behavior on a web-search task => penalty 0"
    ;; The scorer reports cos-good > cos-avoid for this candidate on its own task
    ;; (the use-case beats the 'needs special permissions' avoid-condition) => 0.
    (let [scorer (fn [_c _t] {:cos-avoid 0.40 :cos-good 0.55})
          cand {:document-id "ws"
                :avoid-when ["web search AND needs special elevated permissions"]
                :content "use this to perform a plain web search"}
          {:keys [cos-avoid cos-good penalty]} (dp/score-candidate cand "search the web" scorer cfg)]
      (is (> cos-good cos-avoid) "use-case beats the avoid-condition on this task")
      (is (= 0.0 penalty) "zero false positive: never penalized on its own domain"))))

(deftest score-candidate-fires-on-force-fit-shape
  (testing "refactor force-fit shape: cos-avoid beats cos-good beyond margin => penalty fires"
    (let [scorer (fn [_c _t] {:cos-avoid 0.55 :cos-good 0.30})
          cand {:document-id "rename" :avoid-when ["extract/refactor"] :content "pure rename"}
          {:keys [penalty]} (dp/score-candidate cand "refactor extract" scorer cfg)]
      ;; contrast = 0.55 - 0.30 - 0.05 = 0.20 ; penalty = 2.0 * 0.20 = 0.40
      (is (approx? 0.40 penalty)))))

(deftest score-candidate-fails-open-when-scorer-throws
  (testing "a scorer that throws (e.g. ColBERT bridge down) => penalty 0, NOT a crash"
    ;; The penalty layer is best-effort/additive: a scoring outage must leave the
    ;; LLM ordering untouched (no fabricated penalty), like the reranker fallback.
    (let [boom (fn [_c _t] (throw (ex-info "bridge down" {})))
          {:keys [cos-avoid cos-good penalty]}
          (dp/score-candidate {:avoid-when ["x"] :content "y"} "task" boom cfg)]
      (is (= 0.0 cos-avoid))
      (is (= 0.0 cos-good))
      (is (= 0.0 penalty) "fail open: scorer outage => no penalty, never a crash"))))

(deftest penalize-candidates-fails-open-keeps-llm-order
  (testing "scorer outage => every candidate penalty 0 => LLM order preserved"
    (let [candidates [{:document-id "hi" :fitness-score 0.9}
                      {:document-id "lo" :fitness-score 0.5}]
          boom (fn [_c _t] (throw (ex-info "bridge down" {})))
          out (dp/penalize-candidates nil candidates "t" cfg boom)]
      (is (= ["hi" "lo"] (mapv :document-id out)) "untouched LLM order")
      (is (every? #(= 0.0 (:domain-penalty %)) out))
      (is (approx? 0.9 (:fitness-score (first out))) "fitness unchanged on fail-open"))))

(deftest score-candidate-no-avoid-cannot-be-penalized
  (testing "a candidate with no :avoid-when scores cos-avoid 0 => penalty 0 (never fabricated)"
    ;; A scorer that reflects the real adapters' behavior: no avoid guards => 0.0.
    (let [scorer (fn [c _t] {:cos-avoid (if (seq (dp/avoid-strings c)) 0.9 0.0)
                             :cos-good 0.5})
          cand {:content "use-case"}
          {:keys [cos-avoid penalty]} (dp/score-candidate cand "task" scorer cfg)]
      (is (= 0.0 cos-avoid))
      (is (= 0.0 penalty)))))

;; =============================================================================
;; 7. INTEGRATION — apply-rerank applies the penalty + re-sorts end-to-end,
;;    with the :embedding scorer (config override via ctx) + a FAKE embed-fn so
;;    the assertion is deterministic (real grain read-model for the body; stubbed
;;    ColBERT search + reranker + embeddings — no DJL, no LLM, no bridge).
;; =============================================================================

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/el5-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))]
    {:event-store event-store :cache cache :tenant-id (random-uuid)
     :event-pubsub ps :dscloj-provider :openrouter
     :command-registry (cp/global-command-registry)
     :query-registry (qp/global-query-registry)
     ::cache-dir cache-dir}))

(defn- stop-context [ctx]
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [s (:event-store ctx)] (es/stop s))
  (when-let [dir (::cache-dir ctx)]
    (let [f (java.io.File. dir)]
      (when (.exists f)
        (doseq [c (.listFiles f)] (.delete c))
        (.delete f)))))

(defmacro with-test-ctx [[sym] & body]
  `(let [~sym (create-context)] (try ~@body (finally (stop-context ~sym)))))

(defn- record-body! [ctx target-id body]
  (cp/process-command
    (assoc ctx :command {:command/name :ontology/record-tree-description
                         :command/id (random-uuid)
                         :command/timestamp (time/now)
                         :target-id target-id :body body})))

(defn- fake-list-indexes [_ctx & _opts]
  [{:index-name "ontology-descriptions" :index-id (random-uuid)
    :created-at "2026-05-28T00:00:00Z"}])

(def ^:private shape-winner-body
  "rename-move-symbol-shaped: its :avoid-when guard fits the refactor/extract
   task; its :summary is the use-case (pure rename)."
  {:capabilities ["renames a symbol exhaustively across files"]
   :strengths [{:trait "enumerate refs" :good-when "a pure rename across files"
                :confidence 0.8 :evidence-count 3}]
   :weaknesses [{:trait "stops early" :avoid-when "the task is an extract/refactor, not a pure rename"
                 :confidence 0.8 :evidence-count 3}]
   :representative-uses ["rename calc to compute-tax"]
   :avoid-when ["the task is an extract/refactor, not a pure rename"]
   :summary "behavior-preserving pure rename of a symbol across files"
   :version 1 :consolidated-from-event-count 5})

(def ^:private correct-body
  "Code-building-shaped: its use-case (summary/good-when) fits the extract task;
   its avoid guard does not."
  {:capabilities ["builds new code from a spec"]
   :strengths [{:trait "spec->emit" :good-when "extract a pure helper from a handler, refactor structure"
                :confidence 0.8 :evidence-count 3}]
   :weaknesses [{:trait "over-builds" :avoid-when "a one-line config tweak"
                 :confidence 0.8 :evidence-count 3}]
   :representative-uses ["extract a pricing helper"]
   :avoid-when ["a one-line config tweak"]
   :summary "build/extract/refactor code structure from a handler into a helper"
   :version 1 :consolidated-from-event-count 5})

;; Deterministic fake embeddings: bag-of-words over a tiny vocab so the
;; refactor task lands NEAR the shape-winner's avoid guard ('extract/refactor')
;; AND near the correct body's use-case — exactly the contrast EL-5 must resolve.
(def ^:private vocab
  ["extract" "refactor" "rename" "pure" "helper" "handler" "build" "config" "symbol"])

(defn- bow [s]
  (let [low (clojure.string/lower-case (or s ""))]
    (mapv (fn [w] (if (clojure.string/includes? low w) 1.0 0.0)) vocab)))

(deftest integration-apply-rerank-penalizes-and-resorts
  (testing "apply-rerank: the shape-winner (higher LLM fitness) is penalized + drops below the correct candidate (with the :embedding scorer + fake embed-fn)"
    (with-test-ctx [base-ctx]
      ;; Operator config override: select the :embedding scorer so the
      ;; deterministic fake embed-fn (no DJL, no ColBERT bridge) drives the
      ;; contrast. This ALSO proves :embedding is selectable end-to-end.
      (let [ctx (assoc base-ctx :domain-penalty-config
                       {:scorer :embedding
                        :penalty-scale 2.0 :margin 0.05 :penalty-cap 0.6})
            sw-id (random-uuid) correct-id (random-uuid)]
        (record-body! ctx sw-id shape-winner-body)
        (record-body! ctx correct-id correct-body)
        (Thread/sleep 50)
        (let [task "extract a pure pricing helper from the request handler, refactor preserving behavior"
              candidates
              [{:content (:summary shape-winner-body) :score 0.95 :rank 1
                :document-id (str "sw::" sw-id)
                :document_metadata {:granularity "tree-fingerprint" :target-id (str sw-id)
                                    :confidence 0.7 :last-update "2026"}}
               {:content (:summary correct-body) :score 0.80 :rank 2
                :document-id (str "cb::" correct-id)
                :document_metadata {:granularity "tree-fingerprint" :target-id (str correct-id)
                                    :confidence 0.7 :last-update "2026"}}]]
          (with-redefs [colbert/list-indexes fake-list-indexes
                        colbert/search (fn [_ _] candidates)
                        ;; The LLM SHAPE-MATCHES: gives the shape-winner the
                        ;; HIGHER fitness (the 9/10 bug). EL-5 must override it.
                        reranker/rerank! (fn [_ {:keys [candidates]}]
                                           (mapv (fn [c]
                                                   {:document-id (:document-id c)
                                                    :reasoning "shape"
                                                    :fitness-score (if (clojure.string/starts-with?
                                                                         (:document-id c) "sw::")
                                                                     0.95 0.80)})
                                                 candidates))
                        embedding/embed-text (fn ([s] (bow s)) ([s _] (bow s)))]
            (let [results (ontology/search-descriptions ctx
                            {:query task :rerank-with-intent "behavioral fit" :k 5})
                  by-id (into {} (map (juxt :document-id identity)) results)
                  sw (get by-id (str "sw::" sw-id))
                  cb (get by-id (str "cb::" correct-id))]
              (is (= 2 (count results)) "both candidates survive")
              (is (> (:domain-penalty sw) 0.0)
                  "shape-winner penalized: its avoid-condition (extract/refactor) fits the task")
              (is (= 0.0 (:domain-penalty cb))
                  "correct candidate NOT penalized: its use-case fits the task")
              (is (< (:fitness-score sw) (:fitness-score cb))
                  "EL-5 flips fitness: penalized shape-winner drops below correct")
              (is (= (str "cb::" correct-id) (:document-id (first results)))
                  "RE-SORT: the correct candidate is now #1 despite a lower LLM fitness")
              (is (= :reranker (:rerank-source (first results)))
                  "output contract intact: :rerank-source preserved"))))))))
