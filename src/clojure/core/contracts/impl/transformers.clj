(ns clojure.core.contracts.impl.transformers
  (:use [clojure.core.contracts.impl.funcify :only (funcify)])
  (:require [clojure.core.unify :as unify]
            [clojure.core.contracts.impl.utils :as utils]))


(defn divide-pre-post
  "'[odd? pos? => int?]
     =>
   {:pre (odd? pos?) :post (int?)}
  "
  [cnstr]
  (if (vector? cnstr)
    (let [[L M R] (partition-by #{'=>} cnstr)]
      {:pre  (when (not= L '(=>)) (first L))
       :post (if (= L '(=>)) (first M) (first R))})
    cnstr))


(defn- tag-hocs
  [cnstr]
  (map (fn [form]
         (if (and (seq? form) (= '_ (first form)))
           (list 'fn? (second form))
           form))
       cnstr))

(defn build-constraints-description
  "'[n] '[odd? pos? => int?] \"foo\"
     =>
    [[n] {:pre [(pos? n) (int? n)], :post [(neg? %)]} \"foo\"]"
  [args cnstr docstring]
  (let [cnstr (vec (tag-hocs cnstr))]
    [args
     (->> (divide-pre-post cnstr)
          (utils/manip-map (partial funcify '[%]) [:post])
          (utils/manip-map (partial funcify args) [:pre]))
     docstring]))

(defn- build-condition-body
  [constraint-map body prefix-msg]
  (unify/subst
   '(try
      ((fn []
         ?CNSTR
         ?BODY))
      (catch AssertionError ae
        (throw (AssertionError. (str ?PREFIX ?MSG \newline (.getMessage ae))))))

   {'?CNSTR  constraint-map
    '?PREFIX prefix-msg
    '?BODY   body}))

(defn- build-contract-body
  [[args cnstr descr :as V]]
  (unify/subst     
   '(?PARMS
     (let [ret ?PRE-CHECK]
       ?POST-CHECK))

   {'?ARGS       args
    '?F          'f
    '?PARMS      (vec (list* 'f args))
    '?MSG        descr
    '?PRE-CHECK  (build-condition-body {:pre (:pre cnstr)}   '(apply ?F ?ARGS) "Pre-condition failure: ")
    '?POST-CHECK (build-condition-body {:post (:post cnstr)} 'ret "Post-condition failure: ")}))

(defn- build-contract-bodies
  [constraint-descriptions]
  (for [cnstr constraint-descriptions]
    (build-contract-body cnstr)))

;; # Public API

(defn build-contract-fn-body
  [name docstring raw-constraints]
  (let [raw-cnstr   (partition 2 raw-constraints)
        cnstr-descrs (for [[a c] raw-cnstr]
                       (build-constraints-description a c docstring))] ;; needs work
    (->> cnstr-descrs
         build-contract-bodies
         (list* `fn name))))

