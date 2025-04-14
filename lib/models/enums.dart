// lib/models/enums.dart

/// Énumération pour représenter la proximité détectée d'un obstacle.
/// Utilisée pour catégoriser la valeur de "proximité" la plus élevée
/// (qui correspond à la valeur la plus élevée dans la carte de profondeur inverse MiDaS).
enum ObstacleProximity {
  /// Aucun obstacle significatif détecté au-dessus du seuil de proximité.
  None,

  /// Un obstacle est détecté, mais pas extrêmement proche (valeur de proximité modérée).
  Detected, // On pourrait ajouter Far/Medium si plus de granularité est nécessaire plus tard

  /// Un obstacle est détecté très près de l'appareil (valeur de proximité élevée).
  VeryClose,
}

/// Énumération pour représenter la direction d'un mur potentiel détecté par RANSAC.
enum WallDirection {
  /// Aucun mur dominant détecté par l'algorithme RANSAC.
  None,

  /// Un mur a été détecté principalement sur le côté gauche du champ de vision.
  Left,

  /// Un mur a été détecté principalement sur le côté droit du champ de vision.
  Right,

  /// Un mur a été détecté principalement devant l'utilisateur.
  Front,
}

/// Énumération pour représenter la direction estimée du chemin le plus dégagé.
/// Basée sur l'analyse des zones de faible proximité (plus éloignées) dans la carte de profondeur.
enum FreePathDirection {
  /// Aucun chemin clairement dégagé n'a pu être identifié (peut-être tout est proche/obstrué).
  None,

  /// Le chemin le plus dégagé semble être vers la gauche.
  Left,

  /// Le chemin le plus dégagé semble être droit devant (au centre).
  Center,

  /// Le chemin le plus dégagé semble être vers la droite.
  Right,
}