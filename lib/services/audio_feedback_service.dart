// lib/services/audio_feedback_service.dart

import 'dart:async';
import 'dart:developer';

import 'package:flutter_tts/flutter_tts.dart';

// Importe nos modèles de données
import 'package:assistive_perception_app/models/enums.dart';
import 'package:assistive_perception_app/models/depth_analysis_result.dart';

/// Service responsable de la génération du retour audio pour l'utilisateur
/// en utilisant le moteur Text-To-Speech (TTS) de l'appareil.
class AudioFeedbackService {
  // Instance du moteur TTS
  late FlutterTts _flutterTts;

  // Indicateur d'initialisation
  bool _isInitialized = false;

  // État pour savoir si une annonce est déjà en cours (pour éviter interruptions)
  // On utilise await speakCompletion pour simplifier la gestion ici.

  // --- Gestion du Throttling (Limitation des annonces) ---
  // Durée minimale entre deux annonces de la même catégorie
  static const Duration _throttleDuration = Duration(seconds: 4); // Attendre 4 secondes

  // Date de la dernière annonce pour chaque catégorie
  DateTime _lastObstacleAnnouncement = DateTime.now().subtract(_throttleDuration); // Initialise pour permettre la 1ère annonce
  DateTime _lastWallAnnouncement = DateTime.now().subtract(_throttleDuration);
  DateTime _lastPathAnnouncement = DateTime.now().subtract(_throttleDuration);

  // Langue cible pour la synthèse vocale (Français Canadien pour Montréal)
  static const String targetLanguage = "fr-CA";

  /// Initialise le moteur Text-To-Speech.
  ///
  /// Retourne `true` si succès, `false` sinon.
  Future<bool> initialize() async {
    if (_isInitialized) return true;
    log('Initialisation de AudioFeedbackService...', name: 'AudioFeedbackService');
    try {
      _flutterTts = FlutterTts();

      // Tenter de définir la langue cible
      // Note: La disponibilité de "fr-CA" dépend des langues TTS installées sur l'appareil Android.
      // Il pourrait être judicieux d'ajouter une logique de fallback (ex: "fr-FR" ou "en-US")
      // si la langue cible n'est pas trouvée.
      var result = await _flutterTts.setLanguage(targetLanguage);
      if (result != 1) {
         log("Avertissement: Langue TTS '$targetLanguage' non définie (code retour: $result). Tentative avec fr-FR.", name: 'AudioFeedbackService');
         result = await _flutterTts.setLanguage("fr-FR");
          if (result != 1) {
            log("Avertissement: Langue TTS 'fr-FR' non définie non plus. Utilisation de la langue par défaut.", name: 'AudioFeedbackService');
          } else {
             log("Langue TTS définie sur fr-FR.", name: 'AudioFeedbackService');
          }
      } else {
        log("Langue TTS définie sur $targetLanguage.", name: 'AudioFeedbackService');
      }

      // Ajuster le débit de parole (optionnel, 0.5 = normal)
      await _flutterTts.setSpeechRate(0.55); // Un peu plus rapide que la normale ? A tester.

      // Ajuster la hauteur de la voix (optionnel, 1.0 = normal)
      // await _flutterTts.setPitch(1.0);

      // Indique au moteur TTS d'attendre la fin de la parole avant de considérer l'appel 'speak' comme terminé.
      // Simplifie la gestion de '_isSpeaking'.
      await _flutterTts.awaitSpeakCompletion(true);

      _isInitialized = true;
      log('AudioFeedbackService initialisé avec succès.', name: 'AudioFeedbackService');
      return true;

    } catch (e) {
      log('ERREUR lors de l\'initialisation de FlutterTts: $e', name: 'AudioFeedbackService');
      _isInitialized = false;
      return false;
    }
  }

  /// Fournit un retour vocal basé sur les résultats de l'analyse de profondeur.
  /// Implémente une priorisation et une limitation (throttling) des messages.
  Future<void> provideFeedback(DepthAnalysisResult result) async {
    if (!_isInitialized) {
      log("Avertissement: Tentative de feedback audio avant initialisation.", name: "AudioFeedbackService");
      return;
    }

    // L'utilisation de 'await speakCompletion(true)' lors de l'init
    // et 'await _flutterTts.speak()' ci-dessous devrait empêcher les
    // interruptions/superpositions simples. Pour une gestion plus complexe (files d'attente),
    // il faudrait utiliser les callbacks start/completion/error de flutter_tts.

    final now = DateTime.now();
    String? messageToSpeak; // Le message final à vocaliser (nullable)

    // --- Priorité 1: Obstacles Très Proches ---
    if (result.obstacleProximity == ObstacleProximity.VeryClose && now.difference(_lastObstacleAnnouncement) > _throttleDuration) {
       messageToSpeak = "Attention ! Obstacle très proche";
       _lastObstacleAnnouncement = now;
    }
    // --- Priorité 2: Murs Détectés ---
    else if (result.wallDirection != WallDirection.None && now.difference(_lastWallAnnouncement) > _throttleDuration) {
      switch (result.wallDirection) {
        case WallDirection.Left:
          messageToSpeak = "Mur à gauche";
          break;
        case WallDirection.Right:
          messageToSpeak = "Mur à droite";
          break;
        case WallDirection.Front:
          messageToSpeak = "Mur devant";
          break;
        case WallDirection.None: // Ne devrait pas arriver ici
          break;
      }
      _lastWallAnnouncement = now;
    }
    // --- Priorité 3: Obstacles Détectés (mais pas très proches) ---
     else if (result.obstacleProximity == ObstacleProximity.Detected && now.difference(_lastObstacleAnnouncement) > _throttleDuration) {
       messageToSpeak = "Obstacle devant"; // Message plus générique
       _lastObstacleAnnouncement = now;
     }
    // --- Priorité 4: Chemin Libre ---
    // Annoncé seulement si aucun obstacle ou mur prioritaire n'a été annoncé récemment
    else if (result.freePathDirection != FreePathDirection.None && now.difference(_lastPathAnnouncement) > _throttleDuration) {
      switch (result.freePathDirection) {
        case FreePathDirection.Left:
           messageToSpeak = "Chemin libre à gauche";
           break;
        case FreePathDirection.Center:
           messageToSpeak = "Chemin libre au centre";
           break;
        case FreePathDirection.Right:
           messageToSpeak = "Chemin libre à droite";
           break;
        case FreePathDirection.None: // Ne devrait pas arriver ici
           break;
      }
       _lastPathAnnouncement = now;
    }

    // Si un message a été sélectionné (priorisé et non limité par le temps), le vocaliser.
    if (messageToSpeak != null) {
      try {
        log("TTS Speak: '$messageToSpeak'", name: "AudioFeedbackService");
        await _flutterTts.speak(messageToSpeak);
        // L'await ici attend la fin de la vocalisation grâce à awaitSpeakCompletion(true)
      } catch (e) {
        log("Erreur lors de l'appel TTS speak: $e", name: "AudioFeedbackService");
      }
    }
  }

  /// Arrête la synthèse vocale en cours et libère les ressources.
  Future<void> dispose() async {
    log('Libération de AudioFeedbackService...', name: 'AudioFeedbackService');
    try {
      await _flutterTts.stop(); // Arrête toute parole en cours
    } catch (e) {
       log("Erreur lors de l'appel TTS stop: $e", name: "AudioFeedbackService");
    }
    _isInitialized = false;
    log('AudioFeedbackService libéré.', name: 'AudioFeedbackService');
  }
}