(ns donut.system
  (:refer-clojure :exclude [ref])
  (:require
   [com.rpl.specter :as sp]
   [loom.alg :as la]
   [loom.derived :as ld]
   [loom.graph :as lg]
   [malli.core :as m]
   [meta-merge.core :as mm]))

;; TODO specs for:
;; - component id (group name, component name)

(def ComponentDefinition
  [:map])

(def ComponentName any?)

(def ComponentDefinitions
  [:map-of ComponentName ComponentDefinition])

(def ComponentDefGroupName
  any?)

(def ComponentDefGroups
  [:map-of ComponentDefGroupName ComponentDefinitions])

(def DonutSystem
  [:map
   [::defs any?]
   [::base {:optional true} [:map]]
   [::resolved {:optional true} [:map]]
   [::graph {:optional true} any?]
   [::instances {:optional true} any?]
   [::out {:optional true} [:map]]
   ])

(def system? (m/validator DonutSystem))

(defrecord Ref [key])
(defn ref? [x] (instance? Ref x))
(defn ref [k] (->Ref k))

(defrecord GroupRef [key])
(defn group-ref? [x] (instance? GroupRef x))
(defn group-ref [x] (->GroupRef x))

(defn- default-resolve-refs
  [system component-id]
  (->> system
       (sp/setval [::resolved component-id]
                  (sp/select-one [::defs component-id] system))
       (sp/transform [::resolved component-id (sp/walker (some-fn ref? group-ref? system?))]
                     (fn [{:keys [key] :as r}]
                       (cond
                         ;; don't descend into subsystems
                         (system? r)
                         r

                         (or (group-ref? r) (vector? key))
                         (sp/select-one [::instances key] system)

                         ;; local refs
                         :else
                         (sp/select-one [::instances (first component-id) key] system))))))

(defn- resolve-refs
  "produces an updated component def where refs are replaced by the instance of
  the thing being ref'd. places result under ::resolved"
  [system component-id]
  ;; allow custom resolution fns. right now this is specifically to accommodate
  ;; subsystems.
  (if-let [resolution-fn (sp/select-one [::defs component-id ::resolve-refs] system)]
    (resolution-fn system component-id)
    (default-resolve-refs system component-id)))

(def config-collect-group-path
  "specter path that retains a component's group name"
  [::defs sp/ALL (sp/collect-one sp/FIRST) sp/LAST])

(defn- apply-base
  "merge common component configs"
  [{:keys [::base] :as system}]
  (sp/transform
   [config-collect-group-path sp/MAP-VALS]
   (fn [group-name component-config]
     (mm/meta-merge (group-name base) component-config))
   system))

;;---
;;; generate component graphs
;;---

(defn- component-graph-nodes
  [system]
  (->> system
       (sp/select [config-collect-group-path sp/MAP-KEYS])
       (reduce (fn [graph node]
                 (lg/add-nodes graph node))
               (lg/digraph))))

(defn- expand-refs-for-graph
  "Expand local and group refs without going into subsystems"
  [system]
  (sp/transform [config-collect-group-path (sp/walker (some-fn ref? group-ref? system?))]
                (fn [group-name x]
                  (cond
                    ;; don't descend into subsystems
                    (system? x)
                    x

                    ;; TODO handle group not existing
                    (group-ref? x)
                    (let [group-name (:key x)]
                      {group-name
                       (->> (sp/select [::defs group-name sp/MAP-KEYS] system)
                            (reduce (fn [group-map k]
                                      (assoc group-map k (->Ref [group-name k])))
                                    {}))})

                    (keyword? (:key x))
                    (->Ref [group-name (:key x)])

                    :else
                    x))
                system))

(defn- ref-edges
  [system direction]
  (->> system
       expand-refs-for-graph
       (sp/select [config-collect-group-path
                   sp/ALL (sp/collect-one sp/FIRST) sp/LAST
                   (sp/walker ref?) :key])
       (map (fn [[group-name component-name key]]
              (if (= :topsort direction)
                [[group-name component-name]
                 key]
                [key
                 [group-name component-name]])))))

(defn- component-graph-add-edges
  [graph system direction]
  (reduce (fn [graph edge]
            (lg/add-edges graph edge))
          graph
          (ref-edges system direction)))

(defn gen-graphs
  [system]
  (let [g (component-graph-nodes system)]
    (-> system
        (assoc-in [::graphs :topsort]
                  (component-graph-add-edges g system :topsort))
        (assoc-in [::graphs :reverse-topsort]
                  (component-graph-add-edges g system :reverse-topsort)))))

(def default-component-order
  "which graph to follow to apply signal"
  {:start  :reverse-topsort
   :resume :reverse-topsort})

;;---
;;; signal application
;;---

(defn strk
  "Like `str` but with keywords"
  [& xs]
  (->> xs
       (reduce (fn [s x]
                 (str s
                      (if (keyword? x)
                        (subs (str x) 1)
                        x)))
               "")
       keyword))

(defn- handler-lifecycle-names
  [signal-name]
  {:apply-signal signal-name
   :before       (strk signal-name :-before)
   :after        (strk signal-name :-after)})

(defn- channel-fn
  [system channel component-id]
  (fn ->channel
    ([v]
     (->channel system v))
    ([s v]
     (sp/setval [channel component-id] v s))))

(defn- channel-fns
  [system component-id]
  {:->info       (channel-fn system [::out :info] component-id)
   :->error      (channel-fn system [::out :error] component-id)
   :->warn       (channel-fn system [::out :warn] component-id)
   :->validation (channel-fn system [::out :validation] component-id)
   :->instance   (channel-fn system [::instances] component-id)})

(defn- system-identity
  [_ _ system]
  system)

;;---
;;; computation graph
;;---

(defn gen-signal-computation-graph
  [system signal order]
  (let [component-graph        (get-in system [::graphs order])
        {:keys [before after]} (handler-lifecycle-names signal)]
    (reduce (fn [computation-graph component-node]
              (let [;; generate nodes and edges just for the lifecycle of this
                    ;; component's signal handler
                    computation-graph (->> [before signal after]
                                           (map #(conj component-node %))
                                           (partition 2 1)
                                           (apply lg/add-edges computation-graph))
                    successors        (lg/successors component-graph component-node)]
                (reduce (fn [computation-graph successor-component-node]
                          (lg/add-edges computation-graph
                                        [(conj component-node after)
                                         (conj successor-component-node before)]))
                        computation-graph
                        successors)))
            (lg/digraph)
            (la/topsort component-graph))))

(defn init-signal-computation-graph
  [system signal]
  (assoc system
         ::signal-computation-graph
         (gen-signal-computation-graph system
                                       signal
                                       (get-in system
                                               [::component-order signal]
                                               :topsort))))

(defn signal-stage?
  [stage]
  (not (re-find #"(-before$|-after$)" (name stage))))

(defn- apply-stage-fn
  [system stage-fn component-id]
  (stage-fn (sp/select-one [::resolved component-id] system)
            (sp/select-one [::instances component-id] system)
            (merge system (channel-fns system component-id))))

(defn- stage-result-valid?
  [system]
  (-> system
      ::out
      (select-keys [:errors :validation])
      empty?))

(defn prune-signal-computation-graph
  [system computation-stage-node]
  (update system
          ::signal-computation-graph
          (fn [graph]
            (->> computation-stage-node
                 (ld/subgraph-reachable-from graph)
                 (lg/nodes)
                 (apply lg/remove-nodes graph)))))

(defn remove-signal-computation-stage-node
  [system computation-stage-node]
  (update system
          ::signal-computation-graph
          lg/remove-nodes
          computation-stage-node))

(defn handler-stage-fn
  [system computation-stage-node]
  (let [component-id (vec (take 2 computation-stage-node))
        stage-fn     (or (sp/select-one [::resolved computation-stage-node] system)
                         system-identity)]
    (fn [system]
      (let [stage-result (apply-stage-fn system stage-fn component-id)]
        (if (system? stage-result)
          stage-result
          system)))))

(defn signal-stage-fn
  "computation node will be e.g. [:env :http-port :start]"
  [system computation-stage-node]
  (let [component-id          (vec (take 2 computation-stage-node))
        maybe-signal-constant (sp/select-one [::resolved component-id] system)
        signal-fn             (cond (not maybe-signal-constant)
                                    system-identity

                                    (map? maybe-signal-constant)
                                    (or (sp/select-one [::resolved computation-stage-node] system)
                                        system-identity)

                                    :else
                                    (constantly maybe-signal-constant))

        ;; accomodate setting a constant value for a signal
        signal-fn (if (fn? signal-fn)
                    signal-fn
                    (constantly signal-fn))]
    (fn [system]
      (let [stage-result (apply-stage-fn system signal-fn component-id)]
        (if (system? stage-result)
          stage-result
          (sp/setval [::instances component-id] stage-result system))))))

(defn- computation-stage-fn
  [system [_ _ stage :as computation-stage-node]]
  (if (signal-stage? stage)
    (signal-stage-fn system computation-stage-node)
    (handler-stage-fn system computation-stage-node)))

(defn- prep-system-for-apply-signal-stage
  [system component-id]
  (-> system
      (assoc ::component-id component-id)
      (resolve-refs component-id)))

(defn apply-signal-stage
  [system computation-stage-node]
  (let [component-id   (vec (take 2 computation-stage-node))
        prepped-system (prep-system-for-apply-signal-stage system component-id)
        new-system     ((computation-stage-fn prepped-system computation-stage-node)
                        prepped-system)]
    (if (stage-result-valid? new-system)
      (remove-signal-computation-stage-node new-system computation-stage-node)
      (prune-signal-computation-graph new-system computation-stage-node))))

(defn apply-signal-computation-graph
  [system]
  (loop [{:keys [::signal-computation-graph] :as system} system]
    (let [[computation-stage-node] (la/topsort signal-computation-graph)]
      (if-not computation-stage-node
        system
        (recur (apply-signal-stage system computation-stage-node))))))

;;---
;;; init, apply, etc
;;---

(defn- merge-component-defs
  "Components defined as vectors of maps get merged into a single map"
  [system]
  (sp/transform [::defs sp/MAP-VALS sp/MAP-VALS]
                (fn [component-def]
                  (if (sequential? component-def)
                    (apply merge component-def)
                    component-def))
                system))

(defn init-system
  [maybe-system]
  (->> (merge {::component-order default-component-order}
              maybe-system)
       merge-component-defs
       apply-base
       gen-graphs))

(defn- clean-after-signal-apply
  [system]
  (dissoc system :->error :->info :->instance :->warn :->validation))

(defn signal
  [system signal-name]
  (-> system
      init-system
      (init-signal-computation-graph signal-name)
      (apply-signal-computation-graph)
      (clean-after-signal-apply)))

(defn system-merge
  [& systems]
  (reduce (fn [system subsystem]
            (mm/meta-merge system (init-system subsystem)))
          {}
          systems))

(defn validate-with-malli
  [{:keys [schema]} instance-val {:keys [->validation]}]
  (some-> (and schema (m/explain schema instance-val))
          ->validation))

;;---
;;; subsystems
;;---

(defn- mapify-imports
  [imports]
  (reduce (fn [refmap ref]
            (sp/setval [(:key ref)] ref refmap))
          {}
          imports))

(defn- merge-imports
  "Copies ref'd instances from parent-system into subsystem so that subsystem's
  imported refs will resolve correctly"
  [{:keys [::imports] :as system-component} parent-system]
  (reduce (fn [system {:keys [key]}]
            (sp/setval [::subsystem ::instances key]
                       (sp/select-one [::instances key] parent-system)
                       system))
          system-component
          imports))

(defn- subsystem-resolver
  [parent-system component-id]
  (->> (default-resolve-refs parent-system component-id)
       (sp/transform [::resolved component-id]
                     (fn [system]
                       (merge-imports system parent-system)))))

(defn- forward-channel
  [parent-system channel component-id]
  (if-let [chan-val (sp/select-one [::instances component-id channel] parent-system)]
    (sp/setval [channel component-id]
               chan-val
               parent-system)
    parent-system))

(defn- forward-channels
  [{:keys [::component-id] :as parent-system}]
  (-> parent-system
      (forward-channel [::out :info] component-id)
      (forward-channel [::out :error] component-id)
      (forward-channel [::out :warn] component-id)
      (forward-channel [::out :validation] component-id)))

(defn- forward-start
  [signal-name]
  (fn [resolved _ {:keys [->instance]}]
    (-> resolved
        ::subsystem
        (signal signal-name)
        ->instance
        forward-channels)))

(defn- forward-update
  [signal-name]
  (fn [_ instance {:keys [->instance]}]
    (-> instance
        (signal signal-name)
        ->instance
        forward-channels)))

(defn subsystem-component
  [subsystem & [imports]]
  {:start   (forward-start :start)
   :stop    (forward-update :stop)
   :suspend (forward-update :suspend)
   :resume  (forward-update :resume)

   ::subsystem    subsystem
   ::imports      (mapify-imports imports)
   ::resolve-refs subsystem-resolver})
