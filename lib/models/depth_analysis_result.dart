// lib/models/depth_analysis_result.dart

// Importe les énumérations définies dans le fichier voisin.
import 'package:assistive_perception_app/models/enums.dart';
import 'package:flutter/foundation.dart'; // Importe @immutable

/// Classe immuable pour contenir les résultats agrégés de l'analyse
/// de la carte de profondeur effectuée par le DepthAnalyzer.
///
/// Un objet de cette classe est créé après chaque analyse d'une trame
/// de la caméra (après inférence MiDaS et analyse RANSAC/heuristique).
/// Il regroupe les informations clés à communiquer à l'utilisateur
/// via le service de retour audio (AudioFeedbackService).
///
/// L'annotation '@immutable' indique que les objets de cette classe
/// ne peuvent pas être modifiés après leur création. C'est une bonne pratique
/// pour les objets de données qui transitent dans l'application.
@immutable
class DepthAnalysisResult {
  /// Indique la proximité de l'obstacle le plus proche détecté.
  /// Basé sur la valeur maximale dans la carte de profondeur inverse
  /// qui dépasse un certain seuil (OBSTACLE_CLOSENESS_THRESHOLD).
  final ObstacleProximity obstacleProximity;

  /// Indique la présence et la direction approximative d'un mur
  /// détecté à l'aide de l'algorithme RANSAC sur le nuage de points 3D
  /// généré à partir de la carte de profondeur.
  final WallDirection wallDirection;

  /// Indique la direction estimée de la zone la plus praticable (dégagée),
  /// généralement en recherchant la plus grande zone contiguë où les valeurs
  /// de la carte de profondeur inverse sont inférieures à un seuil
  /// (FREE_PATH_FARNESS_THRESHOLD).
  final FreePathDirection freePathDirection;

  /// Constructeur pour créer une instance de DepthAnalysisResult.
  ///
  /// Les paramètres sont marqués comme 'required', ce qui signifie qu'ils
  /// doivent obligatoirement être fournis lors de la création de l'objet.
  const DepthAnalysisResult({
    required this.obstacleProximity,
    required this.wallDirection,
    required this.freePathDirection,
  });

  /// Méthode pour créer une copie de cet objet avec certaines valeurs modifiées.
  /// Utile si on a besoin de créer une nouvelle instance basée sur une ancienne.
  /// Non strictement nécessaire pour la Phase 1, mais bonne pratique.
  DepthAnalysisResult copyWith({
    ObstacleProximity? obstacleProximity,
    WallDirection? wallDirection,
    FreePathDirection? freePathDirection,
  }) {
    return DepthAnalysisResult(
      obstacleProximity: obstacleProximity ?? this.obstacleProximity,
      wallDirection: wallDirection ?? this.wallDirection,
      freePathDirection: freePathDirection ?? this.freePathDirection,
    );
  }

  /// Fournit une représentation textuelle de l'objet, utile pour le débogage et le logging.
  /// Par exemple, en utilisant `debugPrint(result.toString());`.
  @override
  String toString() {
    return 'DepthAnalysisResult(obstacle: ${obstacleProximity.name}, wall: ${wallDirection.name}, path: ${freePathDirection.name})';
  }

  /// Permet de comparer deux instances de DepthAnalysisResult pour l'égalité.
  /// Deux instances sont considérées comme égales si tous leurs champs sont égaux.
  @override
  bool operator ==(Object other) {
    if (identical(this, other)) return true;

    return other is DepthAnalysisResult &&
        other.obstacleProximity == obstacleProximity &&
        other.wallDirection == wallDirection &&
        other.freePathDirection == freePathDirection;
  }

  /// Fournit un code de hachage basé sur les valeurs des champs.
  /// Nécessaire si vous redéfinissez l'opérateur ==.
  /// Utile si vous stockez ces objets dans des collections basées sur le hachage (Set, Map).
  @override
  int get hashCode =>
      obstacleProximity.hashCode ^
      wallDirection.hashCode ^
      freePathDirection.hashCode;
}