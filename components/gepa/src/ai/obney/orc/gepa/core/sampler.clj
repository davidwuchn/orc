(ns ai.obney.orc.gepa.core.sampler
  "Epoch-shuffled batch sampling for GEPA - Python GEPA parity.

   Implements epoch-based mini-batch sampling similar to k-fold CV / SGD batching:
   - Shuffles training indices at start of each epoch
   - Returns sequential minibatches of batch-size
   - Once all examples are used, reshuffles for next epoch
   - Ensures ALL training examples get used for reflection (no favoritism)

   This is critical for Python GEPA parity because the Python implementation
   uses EpochShuffledBatchSampler to ensure diverse feedback coverage."
  (:require [com.brunobonacci.mulog :as u]))

(defn epoch-shuffled-batch-sampler
  "Epoch-shuffled batch sampling (Python GEPA parity).

   Like k-fold CV / SGD batching:
   - Shuffle training indices at start of each epoch
   - Return sequential minibatches of size `batch-size`
   - Once all examples used, reshuffle for next epoch
   - Ensures ALL training examples get used for reflection

   Args:
     trainset-size: Number of training examples (e.g., 18)
     batch-size: Minibatch size (default 3)
     iteration: Current optimization iteration (0-indexed)
     seed: Random seed for deterministic shuffling (default 42)

   Returns:
     Vector of indices for this iteration's minibatch

   Example with 18 training examples, batch-size 3:
     Epoch 0: [5,2,17,0,8,12,3,15,1,6,14,9,4,11,7,10,16,13]
       Iteration 0: [5, 2, 17]   <- batch 0
       Iteration 1: [0, 8, 12]   <- batch 1
       Iteration 2: [3, 15, 1]   <- batch 2
       Iteration 3: [6, 14, 9]   <- batch 3
       Iteration 4: [4, 11, 7]   <- batch 4
       Iteration 5: [10, 16, 13] <- batch 5 (all 18 examples used!)
     Epoch 1: [reshuffle] -> new random order
       Iteration 6: starts fresh with reshuffled indices"
  ([trainset-size batch-size iteration]
   (epoch-shuffled-batch-sampler trainset-size batch-size iteration 42))

  ([trainset-size batch-size iteration seed]
   {:pre [(pos? trainset-size) (pos? batch-size) (>= iteration 0)]}

   (let [;; Calculate how many batches fit in one epoch
         batches-per-epoch (int (Math/ceil (/ trainset-size batch-size)))

         ;; Calculate which epoch we're in (0-indexed)
         epoch (quot iteration batches-per-epoch)

         ;; Which batch within this epoch (0-indexed)
         batch-in-epoch (mod iteration batches-per-epoch)

         ;; Deterministic shuffle based on epoch + seed
         ;; Different seed per epoch ensures different shuffles
         rng (java.util.Random. (+ seed (* epoch 1000)))

         ;; Create shuffled indices for this epoch
         indices (vec (range trainset-size))
         shuffled (->> indices
                       (map (fn [idx] [idx (.nextDouble rng)]))
                       (sort-by second)
                       (mapv first))

         ;; Calculate slice boundaries for this batch
         start (* batch-in-epoch batch-size)
         end (min (+ start batch-size) trainset-size)
         slice (subvec shuffled start end)]

     ;; If we need padding (last batch smaller), wrap around from shuffled
     (if (< (count slice) batch-size)
       (let [needed (- batch-size (count slice))
             extra (take needed shuffled)]
         (u/log ::padding-last-batch
                :iteration iteration
                :batch-in-epoch batch-in-epoch
                :slice-size (count slice)
                :padding-from-start needed)
         (vec (concat slice extra)))
       slice))))

(defn get-minibatch-for-iteration
  "Get actual training examples for a given iteration.

   Convenience function that applies epoch-shuffled indices to trainset.

   Args:
     trainset: Vector of training examples
     batch-size: Minibatch size
     iteration: Current iteration
     seed: Optional random seed

   Returns:
     Vector of training examples for this minibatch"
  ([trainset batch-size iteration]
   (get-minibatch-for-iteration trainset batch-size iteration 42))

  ([trainset batch-size iteration seed]
   (let [indices (epoch-shuffled-batch-sampler
                   (count trainset) batch-size iteration seed)]
     (mapv #(nth trainset %) indices))))

(defn batches-per-epoch
  "Calculate how many batches fit in one epoch.

   Useful for understanding when examples will start repeating."
  [trainset-size batch-size]
  (int (Math/ceil (/ trainset-size batch-size))))

(defn current-epoch
  "Get which epoch the current iteration is in (0-indexed)."
  [trainset-size batch-size iteration]
  (quot iteration (batches-per-epoch trainset-size batch-size)))

(comment
  ;; Example usage with 18 training examples, batch-size 3
  ;; 18 / 3 = 6 batches per epoch

  (def trainset-size 18)
  (def batch-size 3)

  ;; First epoch - iterations 0-5
  (epoch-shuffled-batch-sampler trainset-size batch-size 0) ; => e.g., [5 2 17]
  (epoch-shuffled-batch-sampler trainset-size batch-size 1) ; => e.g., [0 8 12]
  (epoch-shuffled-batch-sampler trainset-size batch-size 5) ; => e.g., [10 16 13] (last batch of epoch 0)

  ;; Second epoch - iterations 6-11 (reshuffled!)
  (epoch-shuffled-batch-sampler trainset-size batch-size 6) ; => different shuffle

  ;; Verify all indices are covered in one epoch
  (let [all-batches (for [i (range 6)]
                      (epoch-shuffled-batch-sampler trainset-size batch-size i))]
    (= (set (range trainset-size))
       (set (flatten all-batches)))) ; => true

  ;; With real trainset
  (def trainset [{:q "Q1" :a "A1"} {:q "Q2" :a "A2"} {:q "Q3" :a "A3"}
                 {:q "Q4" :a "A4"} {:q "Q5" :a "A5"} {:q "Q6" :a "A6"}])
  (get-minibatch-for-iteration trainset 2 0) ; => first 2 examples (shuffled)
  (get-minibatch-for-iteration trainset 2 1) ; => next 2 examples
  (get-minibatch-for-iteration trainset 2 2) ; => last 2 examples

  ;; After 3 iterations (one epoch), reshuffle
  (get-minibatch-for-iteration trainset 2 3) ; => new shuffle
  )
