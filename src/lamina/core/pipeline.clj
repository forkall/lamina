;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns lamina.core.pipeline
  (:use
    [potemkin :only (unify-gensyms)])
  (:require
    [lamina.core.result :as r]
    [lamina.core.context :as context]
    [clojure.tools.logging :as log])
  (:import
    [lamina.core.result
     ResultChannel
     SuccessResult
     ErrorResult]))

(set! *warn-on-reflection* true)

;;;

(deftype Redirect [pipeline value])

(defprotocol IPipeline
  (run [_ result initial-value value step])
  (error [_ result initial-value ex]))

(defn redirect
  "If returned from a pipeline stage, redirects the pipeline flow to the beginning
   of 'pipeline', with an initial value of 'value'."
  [pipeline value]
  (Redirect. pipeline value))

(defn restart
  ([]
     (Redirect. ::current ::initial))
  ([value]
     (Redirect. ::current value)))

;;;

(defn start-pipeline [pipeline result initial-value value step]
  (loop [pipeline pipeline, initial-value initial-value, value value, step step]
    (let [val (run pipeline result initial-value value step)]
      (if (instance? Redirect val)
        (let [^Redirect redirect val
              value (if (identical? ::initial (.value redirect))
                      initial-value
                      (.value redirect))
              pipeline (if (identical? ::current (.pipeline redirect))
                         pipeline
                         (.pipeline redirect))]
          (recur pipeline value value 0))
        val))))

(defn subscribe [pipeline result initial-val val idx]
  (let [result (or result (r/result-channel))]
    (if-let [ctx (context/context)]
      (r/subscribe val
        (r/result-callback
          #(context/with-context ctx
             (start-pipeline pipeline result initial-val % idx))
          #(context/with-context ctx
             (error pipeline result initial-val %))))
      (r/subscribe val
        (r/result-callback
          #(start-pipeline pipeline result initial-val % idx)
          #(error pipeline result initial-val %))))
    result))

;;;

(defn- split-options [opts+stages]
  (if (map? (first opts+stages))
    [(first opts+stages) (rest opts+stages)]
    [nil opts+stages]))

;; this is a Duff's device-ish unrolling of the pipeline, the resulting code
;; will end up looking something like:
;;
;; (case step
;;   0   (steps 0 to 2 and recur to 3)
;;   1   (steps 1 to 3 and recur to 4)
;;   ...
;;   n-2 (steps n-2 to n and return)
;;   n-1 (steps n-1 to n and return)
;;   n   (step n and return))
;;
;;  the longer the pipeline, the fewer steps per clause, since the JVM doesn't
;;  like big functions.  Currently at eight or more steps, each clause only handles
;;  a single step. 
(defn- unwind-stages [idx stages remaining]
  `(cond
       
     (r/result-channel? val##)
     (let [value# (r/success-value val## ::unrealized)]
       (if (identical? ::unrealized value#)
         (subscribe this## result## initial-val## val## ~idx)
         (recur value# (long ~idx))))
       

     (instance? Redirect val##)
     (let [^Redirect redirect# val##]
       (if (identical? ::current (.pipeline redirect#))
         (let [value# (if (identical? ::initial (.value redirect#))
                        initial-val##
                        (.value redirect#))]
           (recur value# (long 0)))
         val##))

     :else
     ~(if (empty? stages)
        `(if (identical? nil result##)
           (r/success-result val##)
           (do
             (r/success result## val##)
             result##))
        `(let [val## (~(first stages) val##)]
           ~(if (zero? remaining)
              `(recur val## (long ~(inc idx)))
              (unwind-stages
                (inc idx)
                (rest stages)
                (dec remaining)))))))

(defn- complex-error-handler [error-handler]
  `(error [this# result# initial-value# ex#]
     (let [value# (~error-handler ex#)]
       (if (instance? Redirect value#)
         (let [^Redirect redirect# value#
               value# (if (identical? ::initial (.value redirect#))
                        initial-value#
                        (.value redirect#))
               pipeline# (if (identical? ::current (.pipeline redirect#))
                           this#
                           (.pipeline redirect#))]
           (start-pipeline pipeline# result# value# value# 0))
         (do
           (if result#
            (r/error result# ex#)
            (r/error-result ex#)))))))

;; totally ad hoc
(defn- max-depth [num-stages]
  (cond
    (< num-stages 4) 3
    (< num-stages 6) 2
    (< num-stages 8) 1
    :else 0))

(defmacro pipeline [& opts+stages]
  (let [[options stages] (split-options opts+stages)
        {:keys [result error-handler]} options
        len (count stages)
        depth (max-depth len)]
    (unify-gensyms
      `(reify IPipeline
         (run [this## result## initial-val## val# step#] 
           (when (or
                   (identical? nil result##)
                   (identical? nil (r/result result##)))
             (try
               (loop [val## val# step## (long step#)]
                 (case (int step##)
                   ~@(interleave
                       (iterate inc 0)
                       (map
                         #(unwind-stages % (drop % stages) depth)
                         (range (inc len))))))
               (catch Exception ex#
                 (error this## result## initial-val## ex#)))))
         ~(if error-handler
            (complex-error-handler error-handler)
            `(error [_# result# _# ex#]
               (log/error ex# "Unhandled exception in pipeline")
               (if result#
                 (r/error result# ex#)
                 (r/error-result ex#))))
         clojure.lang.IFn
         (invoke [this# val#]
           (start-pipeline this# ~result val# val# 0))))))

(defmacro run-pipeline [value & opts+stages]
  `(let [p# (pipeline ~@opts+stages)
         value# ~value]
     (p# value#)))

(defn read-merge [read-fn merge-fn]
  (pipeline
    (fn [val]
      (run-pipeline (read-fn)
        #(merge-fn val %)))))

(defn complete [value]
  (redirect
    (pipeline (constantly value))
    nil))


