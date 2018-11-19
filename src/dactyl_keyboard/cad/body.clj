;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The Dactyl-ManuForm Keyboard — Opposable Thumb Edition              ;;
;; Keyboard Case Model                                                 ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(ns dactyl-keyboard.cad.body
  (:require [scad-clj.model :exclude [use import] :refer :all]
            [scad-tarmi.threaded :as threaded]
            [dactyl-keyboard.generics :refer [abs NNE ENE ESE WSW WNW NNW
                                              directions-to-unordered-corner]]
            [dactyl-keyboard.cad.misc :as misc]
            [dactyl-keyboard.cad.matrix :as matrix]
            [dactyl-keyboard.cad.key :as key]))


;;;;;;;;;;;;;
;; Masking ;;
;;;;;;;;;;;;;

(defn mask
  "Implement overall limits on passed shapes."
  [getopt & shapes]
  (intersection
    (translate (getopt :mask :center) (apply cube (getopt :mask :size)))
    (apply union shapes)))


;;;;;;;;;;;;;;;;;;;;;;;
;; Key Mount Webbing ;;
;;;;;;;;;;;;;;;;;;;;;;;

;; This connects switch mount plates to one another.

(defn web-shapes
  "A vector of shapes covering the interstices between points in a matrix."
  [coordinate-sequence spotter placer corner-finder]
  (loop [remaining-coordinates coordinate-sequence
         shapes []]
    (if (empty? remaining-coordinates)
      shapes
      (let [coord-here (first remaining-coordinates)
            coord-north (matrix/walk coord-here :north)
            coord-east (matrix/walk coord-here :east)
            coord-northeast (matrix/walk coord-here :north :east)
            fill-here (spotter coord-here)
            fill-north (spotter coord-north)
            fill-east (spotter coord-east)
            fill-northeast (spotter coord-northeast)]
       (recur
         (rest remaining-coordinates)
         (conj
           shapes
           ;; Connecting columns.
           (if (and fill-here fill-east)
             (misc/triangle-hulls
              (placer coord-here (corner-finder ENE))
              (placer coord-east (corner-finder WNW))
              (placer coord-here (corner-finder ESE))
              (placer coord-east (corner-finder WSW))))
           ;; Connecting rows.
           (if (and fill-here fill-north)
             (misc/triangle-hulls
              (placer coord-here (corner-finder WNW))
              (placer coord-north (corner-finder WSW))
              (placer coord-here (corner-finder ENE))
              (placer coord-north (corner-finder ESE))))
           ;; Selectively filling the area between all four possible mounts.
           (misc/triangle-hulls
             (if fill-here (placer coord-here (corner-finder ENE)))
             (if fill-north (placer coord-north (corner-finder ESE)))
             (if fill-east (placer coord-east (corner-finder WNW)))
             (if fill-northeast (placer coord-northeast (corner-finder WSW))))))))))

(defn walk-and-web [columns rows spotter placer corner-finder]
  (web-shapes (matrix/coordinate-pairs columns rows) spotter placer corner-finder))

(defn cluster-web [getopt cluster]
  (apply union
    (walk-and-web
      (getopt :key-clusters :derived :by-cluster cluster :column-range)
      (getopt :key-clusters :derived :by-cluster cluster :row-range)
      (getopt :key-clusters :derived :by-cluster cluster :key-requested?)
      (partial key/cluster-place getopt cluster)
      (partial key/mount-corner-post getopt))))


;;;;;;;;;;;;;;;;;;;
;; Wall-Building ;;
;;;;;;;;;;;;;;;;;;;

(defn wall-segment-offset
  "Compute a 3D offset from one corner of a switch mount to a part of its wall."
  [getopt cluster coord cardinal-direction segment]
  (let [most #(key/most-specific-option getopt (concat [:wall] %) cluster coord)
        thickness (most [:thickness])
        bevel-factor (most [:bevel])
        parallel (most [cardinal-direction :parallel])
        perpendicular (most [cardinal-direction :perpendicular])
        {dx :dx dy :dy} (cardinal-direction matrix/compass-to-grid)
        bevel
          (if (zero? perpendicular)
            bevel-factor
            (* bevel-factor
               (/ perpendicular (abs perpendicular))))]
   (case segment
     0 [0 0 0]
     1 [(* dx thickness) (* dy thickness) bevel]
     2 [(* dx parallel) (* dy parallel) perpendicular]
     3 [(* dx (+ parallel thickness))
        (* dy (+ parallel thickness))
        perpendicular]
     4 [(* dx (+ parallel))
        (* dy (+ parallel))
        (+ perpendicular bevel)])))

(defn wall-vertex-offset
  "Compute a 3D offset from the center of a web post to a vertex on it."
  [getopt directions {:keys [bottom] :or {bottom true}}]
  (let [xy (/ (getopt :case :key-mount-corner-margin) 2)
        z (/ (getopt :case :key-mount-thickness) 2)]
    [(* (apply matrix/compass-dx directions) xy)
     (* (apply matrix/compass-dy directions) xy)
     ((if bottom - +) z)]))

(defn- wall-corner-offset
  "Combined [x y z] offset from the center of a switch mount.
  By default, this goes to one corner of the hem of the mount’s skirt of
  walling and therefore finds the base of full walls."
  [getopt cluster coordinates
   {:keys [directions segment vertex]
    :or {segment 3, vertex false} :as keyopts}]
  (vec
    (map +
      (if directions
        (key/mount-corner-offset getopt directions)
        [0 0 0])
      (if directions
        (wall-segment-offset
          getopt cluster coordinates (first directions) segment)
        [0 0 0])
      (if (and directions vertex)
        (wall-vertex-offset getopt directions keyopts)
        [0 0 0]))))

(defn wall-corner-position
  "Absolute position of the lower wall around a key mount."
  ([getopt cluster coordinates]
   (wall-corner-position getopt cluster coordinates {}))
  ([getopt cluster coordinates keyopts]
   (key/cluster-position getopt cluster coordinates
     (wall-corner-offset getopt cluster coordinates keyopts))))

(defn wall-slab-center-offset
  "Combined [x y z] offset to the center of a vertical wall.
  Computed as the arithmetic average of its two corners."
  [getopt cluster coordinates direction]
  (letfn [(c [turning-fn]
            (wall-corner-offset getopt cluster coordinates
              {:directions [direction (turning-fn direction)]}))]
    (vec (map / (vec (map + (c matrix/left) (c matrix/right))) [2 2 2]))))

;; Functions for specifying parts of a perimeter wall. These all take the
;; edge-walking algorithm’s position and direction upon seeing the need for
;; each part.

(defn wall-straight-body
  "The part of a case wall that runs along the side of a key mount on the
  edge of the board."
  [{:keys [coordinates direction]}]
  (let [facing (matrix/left direction)]
    [[coordinates facing matrix/right] [coordinates facing matrix/left]]))

(defn wall-straight-join
  "The part of a case wall that runs between two key mounts in a straight line."
  [{:keys [coordinates direction]}]
  (let [next-coord (matrix/walk coordinates direction)
        facing (matrix/left direction)]
    [[coordinates facing matrix/right] [next-coord facing matrix/left]]))

(defn wall-outer-corner
  "The part of a case wall that smooths out an outer, sharp corner."
  [{:keys [coordinates direction]}]
  (let [original-facing (matrix/left direction)]
    [[coordinates original-facing matrix/right]
     [coordinates direction matrix/left]]))

(defn wall-inner-corner
  "The part of a case wall that covers any gap in an inner corner.
  In this case, it is import to pick not only the right corner but the right
  direction moving out from that corner."
  [{:keys [coordinates direction]}]
  (let [opposite (matrix/walk coordinates (matrix/left direction) direction)
        reverse (matrix/left (matrix/left direction))]
    [[coordinates (matrix/left direction) (constantly direction)]
     [opposite reverse matrix/left]]))

(defn connecting-wall
  [{:keys [corner] :as position}]
  (case corner
    :outer (wall-outer-corner position)
    nil (wall-straight-join position)
    :inner (wall-inner-corner position)))

;; Edge walking.

(defn wall-edge
  "Produce a sequence of corner posts for the upper or lower part of the edge
  of one wall slab."
  [getopt cluster upper [coord direction turning-fn]]
  (let [extent (key/most-specific-option getopt [:wall direction :extent]
                 cluster coord)
        last-upper-segment (case extent :full 4 :none 0 extent)
        to-ground (= extent :full)
        corner [direction (turning-fn direction)]
        offsetter (partial wall-segment-offset getopt cluster coord direction)
        post (fn [segment]
               (->> (key/mount-corner-post getopt corner)
                    (translate (offsetter segment))
                    (key/cluster-place getopt cluster coord)))]
   (if-not (zero? last-upper-segment)
     (if upper
       (map post (range (inc last-upper-segment)))
       (if (= extent :full)
         (map post [2 3 4]))))))

(defn wall-slab
  "Produce a single shape joining some (two) edges."
  [getopt cluster edges]
  (let [upper (map (partial wall-edge getopt cluster true) edges)
        lower (map (partial wall-edge getopt cluster false) edges)]
   (union
     (apply hull upper)
     (apply misc/bottom-hull lower))))

(defn cluster-wall
  "Walk the edge of a key cluster, walling it in."
  [getopt cluster]
  (apply union
    (reduce
      (fn [coll position]
        (conj coll
          (wall-slab getopt cluster (wall-straight-body position))
          (wall-slab getopt cluster (connecting-wall position))))
      []
      (matrix/trace-between
        (getopt :key-clusters :derived :by-cluster cluster :key-requested?)))))


;;;;;;;;;;;;;;;;;;
;; Rear Housing ;;
;;;;;;;;;;;;;;;;;;

(defn- housing-cube [getopt]
  (let [t (getopt :case :web-thickness)]
   (cube t t t)))

(defn housing-properties
  "Derive characteristics from parameters for the rear housing."
  [getopt]
  (let [cluster (getopt :case :rear-housing :position :cluster)
        row (last (getopt :key-clusters :derived :by-cluster cluster :row-range))
        coords (getopt :key-clusters :derived :by-cluster cluster :coordinates-by-row row)
        pairs (into [] (for [coord coords corner [NNW NNE]] [coord corner]))
        getpos (fn [[coord corner]]
                 (key/cluster-position getopt cluster coord
                   (key/mount-corner-offset getopt corner)))
        y-max (apply max (map #(second (getpos %)) pairs))
        getoffset (partial getopt :case :rear-housing :position :offsets)
        y-roof-s (+ y-max (getoffset :south))
        y-roof-n (+ y-roof-s (getoffset :north))
        z (getopt :case :rear-housing :height)
        roof-sw [(- (first (getpos (first pairs))) (getoffset :west)) y-roof-s z]
        roof-se [(+ (first (getpos (last pairs))) (getoffset :east)) y-roof-s z]
        roof-nw [(first roof-sw) y-roof-n z]
        roof-ne [(first roof-se) y-roof-n z]]
   {:west-end-coord (first coords)
    :east-end-coord (last coords)
    :coordinate-corner-pairs pairs
    ;; [x y z] coordinates of the corners of the topmost part of the roof:
    :sw roof-sw
    :se roof-se
    :nw roof-nw
    :ne roof-ne}))

(defn- housing-roof
  "A cuboid shape between the four corners of the rearing housing’s roof."
  [getopt]
  (let [getcorner (partial getopt :case :rear-housing :derived)]
    (apply hull
      (map #(translate (getcorner %) (housing-cube getopt))
           [:nw :ne :se :sw]))))

(defn- housing-segment-offset
  "Compute the [x y z] coordinate offset from a rear housing roof corner."
  [getopt cardinal-direction segment]
  (let [cluster (getopt :case :rear-housing :position :cluster)
        key (partial getopt :case :rear-housing :derived)
        wall (partial wall-segment-offset getopt cluster)]
   (case cardinal-direction
     :west (wall (key :west-end-coord) cardinal-direction segment)
     :east (wall (key :east-end-coord) cardinal-direction segment)
     :north (if (= segment 0) [0 0 0] [0 1 -1]))))

(defn- housing-placement
  "Place passed shape in relation to a corner of the rear housing’s roof."
  [translate-fn getopt corner segment subject]
  (->> subject
       (translate-fn (getopt :case :rear-housing :derived
                       (directions-to-unordered-corner corner)))
       (translate-fn (housing-segment-offset getopt (first corner) segment))))

(def housing-place
  "Akin to cluster-place but with a wall segment."
  (partial housing-placement translate))

(def housing-position
  "Akin to cluster-position but with a wall segment."
  (partial housing-placement (partial map +)))

(defn- housing-west-wall
  "The western outer wall connecting case to rear housing."
  [getopt upper]
  (let [cluster (getopt :case :rear-housing :position :cluster)
        wec (getopt :case :rear-housing :derived :west-end-coord)]
    (wall-edge getopt cluster upper [wec :west matrix/right])))

(defn- housing-east-wall
  "The eastern outer wall connecting case to rear housing."
  [getopt upper]
  (let [cluster (getopt :case :rear-housing :position :cluster)
        eec (getopt :case :rear-housing :derived :east-end-coord)]
    (wall-edge getopt cluster upper [eec :east matrix/left])))

(defn- housing-straight-wall-post-fn
  "Close over a function that places cubes for the completely straight main
  walls of the rear housing."
  [getopt segments]
  (fn [pair]
    (map #(housing-place getopt pair % (housing-cube getopt)) segments)))

(defn- housing-straight-wall-cubes
  "A sequence of cubes that form part of a straight wall."
  [getopt segments corners]
  (map (housing-straight-wall-post-fn getopt segments) corners))

(defn- housing-wall-sequences
  "Some portion of the completely straight main walls of the rear housing."
  ([getopt segments]
   (housing-wall-sequences getopt segments identity))
  ([getopt segments transformer]
   (housing-straight-wall-cubes getopt segments
     (transformer [WSW WNW NNW NNE ENE ESE]))))

(defn- housing-straight-single
  "A convenience for selecting just one post or piece of the straight walls."
  [getopt selector]
  (housing-wall-sequences getopt [1] (fn [corners] [(selector corners)])))

(defn- housing-wall-level
  "The west, north and east walls of the rear housing with connections to the
  ordinary case wall."
  [getopt is-upper-level joiner segments]
  (apply misc/pairwise-hulls
    (reduce
      (fn [coll shapes] (conj coll (apply joiner shapes)))
      []
      (concat
        [(housing-west-wall getopt is-upper-level)]
        (housing-wall-sequences getopt segments)
        [(housing-east-wall getopt is-upper-level)]))))

(defn- housing-outer-wall
  "The complete walls of the rear housing: Vertical walls and a bevelled upper
  level that meets the roof."
  [getopt]
  (union
    (housing-wall-level getopt true hull [0 1])
    (housing-wall-level getopt false misc/bottom-hull [1])))

(defn- housing-web
  "An extension of a key cluster’s webbing onto the roof of the rear housing."
  [getopt]
  (let [cluster (getopt :case :rear-housing :position :cluster)
        pos-corner (fn [coord corner]
                     (key/cluster-position getopt cluster coord
                       (key/mount-corner-offset getopt corner)))
        sw (getopt :case :rear-housing :derived :sw)
        se (getopt :case :rear-housing :derived :se)
        x (fn [coord corner]
            (max (first sw)
                 (min (first (pos-corner coord corner))
                      (first se))))
        y (second sw)
        z (getopt :case :rear-housing :height)]
   (apply misc/pairwise-hulls
     (reduce
       (fn [coll [coord corner]]
         (conj coll
           (hull (key/cluster-place getopt cluster coord
                   (key/mount-corner-post getopt corner))
                 (translate [(x coord corner) y z]
                   (housing-cube getopt)))))
       []
       (getopt :case :rear-housing :derived :coordinate-corner-pairs)))))

(defn- housing-foot
  "A simple ground-level plate at one corner of the housing."
  [getopt]
  (let [base (take 2 (getopt :case :rear-housing :derived :nw))
        w (min 10 (getopt :case :rear-housing :position :offsets :north))]
   (extrude-linear
     {:height (getopt :case :foot-plates :height) :center false}
     (polygon [base (map + base [0 (- w)]) (map + base [w 0])]))))

(defn- housing-mount-place [getopt side shape]
  (let [d (getopt :case :rear-housing :fasteners :diameter)
        offset (getopt :case :rear-housing :fasteners side :offset)
        n (getopt :case :rear-housing :position :offsets :north)
        t (getopt :case :web-thickness)
        h (threaded/datum d :hex-nut-height)
        [sign base] (case side
                      :west [+ (getopt :case :rear-housing :derived :sw)]
                      :east [- (getopt :case :rear-housing :derived :se)])
        near (vec (map + base [(+ (- (sign offset)) (sign d)) d (/ (+ t h) -2)]))
        far (vec (map + near [0 (- n d d) 0]))]
   (hull
     (translate near shape)
     (translate far shape))))

(defn- housing-mount-positive [getopt side]
  (let [d (getopt :case :rear-housing :fasteners :diameter)
        w (* 2.2 d)]
   (housing-mount-place getopt side
     (cube w w (threaded/datum d :hex-nut-height)))))

(defn- housing-mount-negative [getopt side]
  (let [d (getopt :case :rear-housing :fasteners :diameter)
        compensator (getopt :dfm :derived :compensator)
        mount-side (* 2.2 d)]
   (union
     (housing-mount-place getopt side
       (cylinder (/ d 2) 20))
     (if (getopt :case :rear-housing :fasteners :bosses)
       (housing-mount-place getopt side
         (threaded/nut :iso-size d :compensator compensator :negative true))))))

(defn rear-housing
  "A squarish box at the far end of a key cluster."
  [getopt]
  (let [prop (partial getopt :case :rear-housing :fasteners)
        pair (fn [function]
               (union
                 (if (prop :west :include) (function getopt :west))
                 (if (prop :east :include) (function getopt :east))))]
   (difference
     (union
       (housing-roof getopt)
       (housing-web getopt)
       (if (getopt :case :rear-housing :west-foot)
         (housing-foot getopt))
       (housing-outer-wall getopt)
       (if (prop :bosses) (pair housing-mount-positive)))
     (pair housing-mount-negative))))


;;;;;;;;;;;;;;;;;;;
;; Tweak Plating ;;
;;;;;;;;;;;;;;;;;;;

(defn- tweak-posts
  "(The hull of) one or more corner posts from a single key mount."
  [getopt key-alias directions first-segment last-segment]
  (if (= first-segment last-segment)
    (let [keyinfo (getopt :key-clusters :derived :aliases key-alias)
          {cluster :cluster coordinates :coordinates} keyinfo
          offset (wall-segment-offset getopt cluster coordinates
                  (first directions) first-segment)]
     (->> (key/mount-corner-post getopt directions)
          (translate offset)
          (key/cluster-place getopt cluster coordinates)))
    (apply hull (map #(tweak-posts getopt key-alias directions %1 %1)
                     (range first-segment (inc last-segment))))))

(declare tweak-plating)

(defn- tweak-map
  "Treat a map-type node in the configuration."
  [getopt node]
  (let [parts (get node :chunk-size)
        to-ground (get node :to-ground false)
        combo (or to-ground parts)
        prefix (if (get node :highlight) -# identity)
        shapes (reduce (partial tweak-plating getopt) [] (:hull-around node))]
   (prefix
     (apply (if parts union (if to-ground misc/bottom-hull hull))
       (if parts
         (map (partial apply (if to-ground misc/bottom-hull hull))
              (partition parts 1 shapes))
         shapes)))))

(defn- tweak-plating
  "A reducer."
  [getopt coll node]
  (conj coll
    (if (map? node)
      (tweak-map getopt node)
      (apply (partial tweak-posts getopt) node))))

(defn wall-tweaks
  "User-requested additional shapes."
  [getopt]
  (apply union
    (reduce (partial tweak-plating getopt) [] (getopt :case :tweaks))))


;;;;;;;;;;;;;;;;;;
;; Bottom Plate ;;
;;;;;;;;;;;;;;;;;;

(defn- floor-finder
  "Return a sequence of xy-coordinate pairs for exterior wall vertices."
  [getopt cluster edges]
  (map
    (fn [[coord direction turning-fn]]
      (let [key [:wall direction :extent]
            extent (key/most-specific-option getopt key cluster coord)]
        (when (= extent :full)
          (take 2
            (wall-corner-position getopt cluster coord
              {:directions [direction (turning-fn direction)] :vertex true})))))
    edges))

(defn- floor-polygon
  "A polygon approximating a floor-level projection of a key clusters’s wall."
  [getopt cluster]
  (polygon
    (filter some?  ; Get rid of edges with partial walls.
      (mapcat identity  ; Flatten floor-finder output by one level only.
        (reduce
          (fn [coll position]
            (conj coll
              (floor-finder getopt cluster (connecting-wall position))))
          []
          (matrix/trace-between
            (getopt :key-clusters :derived :by-cluster cluster :key-requested?)))))))

(defn bottom-plate
  "A model of a bottom plate for the entire case."
  [getopt]
  (color [150/255 90/255 70/255 1]
    (extrude-linear
      {:height (getopt :case :bottom-plate :thickness), :center false}
      (union
        (key/metacluster floor-polygon getopt)
        (if (getopt :case :rear-housing :include)
          ;; Take care to hull the straight and other parts of the rear housing
          ;; separately because the connection between them may be concave.
          (cut
            (misc/bottom-hull  ; The cuboid part of the housing.
              (housing-wall-sequences getopt [1]))
            (misc/bottom-hull  ; The connection’s four corners.
              (housing-west-wall getopt false)
              (housing-straight-single getopt first)
              (housing-east-wall getopt false)
              (housing-straight-single getopt last))))))))
