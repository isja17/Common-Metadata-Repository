(ns cmr.search.services.query-execution.has-granules-or-opensearch-results-feature
  "This enables the :has-granules-or-opensearch feature for collection search results. When it is enabled
  collection search results will include a boolean flag indicating whether the collection has
  any granules at all as indicated by provider holdings."
  (:require
   [cmr.common-app.services.search.elastic-search-index :as common-esi]
   [cmr.common-app.services.search.group-query-conditions :as gc]
   [cmr.common-app.services.search.parameters.converters.nested-field :as nf]
   [cmr.common-app.services.search.query-execution :as query-execution]
   [cmr.common-app.services.search.query-model :as qm]
   [cmr.common.cache :as cache]
   [cmr.common.cache.in-memory-cache :as mem-cache]
   [cmr.common-app.config :as common-config]
   [cmr.common.jobs :refer [defjob]]
   [cmr.search.data.elastic-search-index :as idx]))

(def REFRESH_HAS_GRANULES_OR_OPENSEARCH_MAP_JOB_INTERVAL
  "The frequency in seconds of the refresh-has-granules-or-opensearch-map-job"
  ;; default to 1 hour
  3600)

(def has-granules-or-opensearch-cache-key
  :has-granules-or-opensearch-map)

(defn create-has-granules-or-opensearch-map-cache
  "Returns a 'cache' which will contain the cached has_granules map."
  []
  (mem-cache/create-in-memory-cache))

(defn get-opensearch-collections
  "Returns the collection granule count by searching elasticsearch by aggregation"
  [context provider-ids]
  (let [condition (nf/parse-nested-condition :tags {:tag-key (common-config/opensearch-tag)} false false)
        query (qm/query {:concept-type :collection
                         :condition condition
                         :page-size :unlimited})
        results (common-esi/execute-query context query)]
    (into {}
          (for [coll-id (map :_id (get-in results [:hits :hits]))]
            [coll-id 1]))))

(defn- collection-granule-counts->has-granules-or-opensearch-map
  "Converts a map of collection ids to granule counts to a map of collection ids to true or false
  of whether the collection has any granules"
  [coll-gran-counts]
  (into {} (for [[coll-id num-granules] coll-gran-counts]
             [coll-id (> num-granules 0)])))

(defn refresh-has-granules-or-opensearch-map
  "Gets the latest provider holdings and updates the has-granules-or-opensearch-map stored in the cache."
  [context]
  (let [has-granules-or-opensearch-map (collection-granule-counts->has-granules-or-opensearch-map
                                        (merge
                                         (idx/get-collection-granule-counts context nil)
                                         (get-opensearch-collections context nil)))]
    (cache/set-value (cache/context->cache context has-granules-or-opensearch-cache-key)
                     :has-granules-or-opensearch has-granules-or-opensearch-map)))

(defn get-has-granules-or-opensearch-map
  "Gets the cached has granules map from the context which contains collection ids to true or false
  of whether the collections have granules or not. If the has-granules-or-opensearch-map has not yet been cached
  it will retrieve it and cache it."
  [context]
  (let [has-granules-or-opensearch-map-cache (cache/context->cache context has-granules-or-opensearch-cache-key)]
    (cache/get-value has-granules-or-opensearch-map-cache
                     :has-granules-or-opensearch
                     (fn []
                       (collection-granule-counts->has-granules-or-opensearch-map
                        (merge
                         (idx/get-collection-granule-counts context nil)
                         (get-opensearch-collections context nil)))))))

(defjob RefreshHasGranulesOrOpenSearchMapJob
  [ctx system]
  (refresh-has-granules-or-opensearch-map {:system system}))

(def refresh-has-granules-or-opensearch-map-job
  {:job-type RefreshHasGranulesOrOpenSearchMapJob
   :interval REFRESH_HAS_GRANULES_OR_OPENSEARCH_MAP_JOB_INTERVAL})