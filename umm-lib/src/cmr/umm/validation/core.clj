(ns cmr.umm.validation.core
  (:require [bouncer.core :as b]
            [bouncer.validators :as v]
            [cmr.umm.collection :as c]
            [cmr.umm.granule :as g]
            [cmr.common.services.errors :as e])
  (:import cmr.umm.collection.UmmCollection
           cmr.umm.granule.UmmGranule))

(def spatial-coverage-validations
  "Defines spatial coverage validations for collections."
  {:granule-spatial-representation [v/required]})

(def collection-validations
  "Defines validations for collections"
  {:spatial-coverage spatial-coverage-validations
   :access-value [v/number]})

(def granule-validations
  "Defines validations for granules"
  {})

(def umm-validations
  "A list of validations by type"
  {UmmCollection collection-validations
   UmmGranule granule-validations})

(def umm-metadata-path-map
  "A map of metadata-format concept-type tuples to a map that maps UMM fields to the equivalent
  field names for that metadata-format. The values in that map are either a string field name
  or a tuple of a field name and another map of the fields within that part of the tree."
  ;; We need to make this work for something like DIF temporal or spatial where the mappings
  ;; won't be as straightforward.

  {[:echo10 :collection]
   {:access-value "RestrictionFlag"
    :spatial-coverage ["Spatial" {:granule-spatial-representation "GranuleSpatialRepresentation"}]}

   [:dif :collection]
   {;; This XPath will select the granule spatial representation.
    ;; /DIF/./Extended_Metadata/Metadata[Name="GranuleSpatialRepresentation"]/Value
    :spatial-coverage ["." {:granule-spatial-representation
                            {:xpath "Extended_Metadata/Metadata[Name=\"GranuleSpatialRepresentation\"]/Value"
                             :human "GranuleSpatialRepresentation"}}]}
   })


(defn- umm-path->format-type-path
  "Converts a path of UMM field keywords into a path specific for the metadata format and concept type."
  [metadata-format concept-type umm-path]
  (loop [format-type-map (umm-metadata-path-map [metadata-format concept-type])
         field-path umm-path
         new-path []]
    (if (seq field-path)
      (let [format-type-map-value (get format-type-map (first field-path))
            ;; The value in the map could be a vector containing the name of the equivalent element and a submap
            ;; or in the case of a leaf node it will just be the name of the element.
            [format-name-or-map submap] (if (sequential? format-type-map-value)
                                   format-type-map-value
                                   [format-type-map-value])
            format-name (or (:human format-name-or-map) format-name-or-map)]
        (when-not format-type-map-value
          (e/internal-error!
            (format
              "Could not find umm-metadata-path-map entry for %s of metadata-format %s and concept-type %s"
              (pr-str umm-path) metadata-format concept-type)))

        (recur submap (rest field-path) (conj new-path format-name)))
      new-path)))

(defn- message-fn
  "The message function used with bouncer validation. Avoids constructing the individual messages
  during validation so they can be customized per format later after validation is complete."
  [m]
  {:default-message-format (get-in m [:metadata :default-message-format])
   :value (:value m)})

(defn- flatten-field-errors
  "Takes a nested set of errors as would be returned by bouncer and returns a flattened set of tuples
  containing the umm field path and the errors."
  ([field-errors]
   (flatten-field-errors field-errors []))
  ([field-errors field-path]
   (mapcat (fn [[field v]]
             (if (sequential? v)
               [[(conj field-path field) v]]
               (flatten-field-errors v (conj field-path field))))
           field-errors)))

(defn- create-format-specific-error-messages
  "Takes a list of field error tuples and errors (as returned by message-fn) and formats each error
  using the name appropriate for the metadata format. For example RestrictionFlag would be returned
  in an error message instead of the umm term Access value for ECHO10 format data."
  [metadata-format concept-type field-errors]
  (for [[field-path errors] field-errors
        :let [format-type-path (umm-path->format-type-path metadata-format concept-type field-path)]
        {:keys [default-message-format value]} errors]
    (format default-message-format (last format-type-path))))

(defn validate
  "Validates the umm record returning a list of error messages appropriate for the given metadata
  format and concept type. Returns an empty sequence if it is valid."
  [metadata-format concept-type umm]
  (->> (umm-validations (type umm))
       (b/validate message-fn umm)
       first
       flatten-field-errors
       (create-format-specific-error-messages metadata-format concept-type)))


(comment

  (validate :echo10 :collection (c/map->UmmCollection {:access-value "f"}))
  (validate :dif :collection (c/map->UmmCollection {}))

  )