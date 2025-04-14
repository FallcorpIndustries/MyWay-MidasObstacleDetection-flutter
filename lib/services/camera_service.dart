// lib/services/camera_service.dart

import 'dart:async'; // Pour Future, StreamSubscription
import 'dart:developer'; // Pour la fonction log() plus détaillée que debugPrint

import 'package:camera/camera.dart'; // Importe le plugin camera
import 'package:flutter/foundation.dart'; // Pour kIsWeb, etc. (pas utilisé ici mais souvent utile)
import 'package:flutter/services.dart'; // Pour PlatformException

/// Service responsable de la gestion de la caméra de l'appareil.
///
/// Fournit des méthodes pour initialiser la caméra, démarrer/arrêter le flux d'images,
/// et nettoyer les ressources. Gère également la logique essentielle pour éviter
/// le traitement excessif des images (frame skipping).
class CameraService {
  // Contrôleur principal pour interagir avec la caméra matérielle.
  // Il est nullable (?) car il n'est pas initialisé immédiatement.
  CameraController? _controller;

  // Liste des caméras disponibles sur l'appareil.
  List<CameraDescription>? _cameras;

  // La caméra spécifique (arrière) sélectionnée pour utilisation.
  CameraDescription? _selectedCamera;

  // Indique si le service et le contrôleur de caméra sont initialisés avec succès.
  bool _isInitialized = false;

  // === Logique de Saut d'Image (Frame Skipping) ===
  // Indicateur pour savoir si une image de la caméra est EN COURS de traitement
  // par notre pipeline (pré-traitement, inférence, analyse).
  // C'est CRUCIAL pour la performance : si nous recevons une nouvelle image alors
  // que la précédente n'a pas fini d'être traitée, nous ignorons la nouvelle
  // pour éviter une accumulation de latence et une surcharge du CPU/GPU.
  bool _isProcessingFrame = false;

  // Indique si le flux d'images de la caméra est actuellement actif.
  bool _isStreaming = false;

  // Getter public pour permettre à l'interface utilisateur (UI) d'accéder
  // au CameraController (par exemple, pour afficher CameraPreview).
  // Retourne null si le contrôleur n'est pas encore initialisé.
  CameraController? get controller => _controller;

   

  // Getter pour savoir si le service est prêt à être utilisé.
  bool get isInitialized => _isInitialized;

  bool get isStreaming => _isStreaming;

  /// Initialise le service de caméra.
  ///
  /// Recherche les caméras disponibles, sélectionne la caméra arrière,
  /// crée et initialise le CameraController. Gère les permissions implicitement
  /// (le plugin camera demande la permission si elle n'est pas accordée).
  ///
  /// Retourne `true` si l'initialisation réussit, `false` sinon.
  Future<bool> initialize() async {
    // Empêche la ré-initialisation si déjà fait.
    if (_isInitialized) {
      log('CameraService déjà initialisé.', name: 'CameraService');
      return true;
    }

    log('Initialisation de CameraService...', name: 'CameraService');
    try {
      // Récupère la liste des caméras disponibles sur l'appareil.
      _cameras = await availableCameras();
      if (_cameras == null || _cameras!.isEmpty) {
        log('ERREUR: Aucune caméra disponible trouvée sur cet appareil.', name: 'CameraService');
        return false;
      }
      log('${_cameras!.length} caméras trouvées.', name: 'CameraService');

      // Recherche la caméra arrière (back-facing).
      // La plupart des applications d'assistance visuelle utilisent la caméra arrière.
      for (CameraDescription camera in _cameras!) {
        if (camera.lensDirection == CameraLensDirection.back) {
          _selectedCamera = camera;
          log('Caméra arrière sélectionnée: ${camera.name}', name: 'CameraService');
          break; // Sort de la boucle une fois la caméra arrière trouvée
        }
      }

      // Gère le cas où aucune caméra arrière n'est trouvée (rare mais possible).
      if (_selectedCamera == null) {
        log('ERREUR: Aucune caméra arrière trouvée. Utilisation de la première caméra disponible.', name: 'CameraService');
        // Solution de repli : utiliser la première caméra de la liste
        _selectedCamera = _cameras![0];
      }

      // Crée l'instance du CameraController.
      // - _selectedCamera: La caméra matérielle à utiliser.
      // - ResolutionPreset.high: Définit la résolution de la prévisualisation et du flux d'images.
      //     Options courantes : low, medium, high, veryHigh, ultraHigh, max.
      //     'high' est un bon compromis pour la qualité et la performance.
      //     'medium' pourrait être envisagé si la performance sur l'appareil cible est insuffisante.
      //     Une résolution plus élevée donne plus de détails mais consomme plus de CPU/batterie
      //     et augmente la taille des données à traiter.
      // - enableAudio: false -> Nous n'avons pas besoin de l'audio de la caméra pour cette application.
      // - imageFormatGroup: Définit le format des images dans le flux (startImageStream).
      //     ImageFormatGroup.yuv420 est courant sur Android et souvent le plus performant
      //     car c'est souvent le format natif de la caméra. Nous devrons le convertir en RGB
      //     pour notre modèle TFLite (ce sera fait via FFI en C++).
      //     ImageFormatGroup.bgra8888 fournirait directement du RGB(A) mais peut être moins performant.
      _controller = CameraController(
        _selectedCamera!,
        ResolutionPreset.high, // Ou .medium pour tester la performance
        enableAudio: false,
        imageFormatGroup: ImageFormatGroup.yuv420,
      );

      // Initialise le contrôleur. C'est ici que la connexion réelle à la caméra
      // est établie. Peut échouer si la caméra est utilisée par une autre app
      // ou si les permissions ne sont pas accordées.
      await _controller!.initialize();

      // Si nous arrivons ici, tout s'est bien passé.
      _isInitialized = true;
      log('CameraService initialisé avec succès.', name: 'CameraService');
      return true;

    } on CameraException catch (e) {
      // Gère les erreurs spécifiques au plugin camera.
      log('ERREUR CameraException lors de l\'initialisation: ${e.code}\n${e.description}', name: 'CameraService');
      _isInitialized = false;
      return false;
    } on PlatformException catch (e) {
       // Gère les erreurs potentielles de la plateforme sous-jacente.
      log('ERREUR PlatformException lors de l\'initialisation: ${e.code}\n${e.message}', name: 'CameraService');
       _isInitialized = false;
       return false;
    } catch (e) {
      // Gère toute autre erreur inattendue.
      log('ERREUR Inattendue lors de l\'initialisation: $e', name: 'CameraService');
      _isInitialized = false;
      return false;
    }
  }

  /// Démarre le flux d'images de la caméra.
  ///
  /// [onFrameAvailable] est une fonction callback qui sera appelée pour chaque
  /// nouvelle image reçue de la caméra. Cette fonction DOIT retourner un Future<void>
  /// et sera attendue (`await`) pour implémenter correctement le saut d'image.
  ///
  /// La logique de saut d'image (`_isProcessingFrame`) est appliquée ici pour
  /// garantir que nous ne commençons pas à traiter une nouvelle image avant
  /// que le traitement de la précédente ne soit terminé.
  Future<void> startStreaming(Future<void> Function(CameraImage image) onFrameAvailable) async {
    // Vérifie si le service est initialisé et que le contrôleur existe.
    if (!_isInitialized || _controller == null) {
      log('ERREUR: CameraService non initialisé. Impossible de démarrer le streaming.', name: 'CameraService');
      return;
    }
    // Vérifie si le streaming est déjà en cours.
    if (_isStreaming) {
      log('Avertissement: Le streaming est déjà actif.', name: 'CameraService');
      return;
    }

    log('Démarrage du flux d\'images...', name: 'CameraService');
    try {
      // Réinitialise le flag de traitement au cas où.
      _isProcessingFrame = false;

      // Démarre le flux d'images. La fonction fournie sera appelée pour chaque image.
      await _controller!.startImageStream((CameraImage image) async {
        // --- Début de la logique de Saut d'Image (Frame Skipping) ---
        if (_isProcessingFrame) {
          // Si une image précédente est toujours en cours de traitement,
          // ignore cette nouvelle image et retourne immédiatement.
          // log('Image sautée (précédente en cours de traitement)', name: 'CameraService'); // Décommenter pour déboguer le frame skipping
          return;
        }
        // --- Fin de la logique de Saut d'Image ---

        // Marque qu'une image est maintenant en cours de traitement.
        _isProcessingFrame = true;

        try {
          // Appelle la fonction de callback fournie pour traiter l'image.
          // Nous utilisons 'await' ici, en supposant que onFrameAvailable
          // contient toute la logique de traitement (preprocessing, inference, analysis)
          // et retourne un Future qui se complète quand le traitement est fini.
          await onFrameAvailable(image);

        } catch (e) {
          // Enregistre toute erreur survenant pendant le traitement de l'image.
          log('ERREUR dans le callback onFrameAvailable: $e', name: 'CameraService');
        } finally {
          // IMPORTANT : Assure que le flag est réinitialisé, MÊME SI une erreur
          // se produit dans le callback onFrameAvailable.
          // Cela permet au traitement de l'image suivante de commencer.
          _isProcessingFrame = false;
        }

        // Format de l'image (CameraImage) :
        // Sur Android, avec ImageFormatGroup.yuv420, l'image est généralement au format YUV_420_888.
        // Elle est composée de 3 plans (planes):
        // - planes[0]: Luminance (Y). Contient les données de luminosité.
        // - planes[1]: Chrominance U (Cb). Contient les données de couleur bleu-projection.
        // - planes[2]: Chrominance V (Cr). Contient les données de couleur rouge-projection.
        //
        // ATTENTION aux Strides (bytesPerRow) :
        // La largeur d'une ligne en mémoire (bytesPerRow) peut être PLUS GRANDE que la largeur
        // réelle de l'image (width * bytesPerPixel) à cause du "padding" (rembourrage) pour
        // l'alignement mémoire. Il est CRUCIAL d'utiliser `image.planes[i].bytesPerRow`
        // lors de la lecture des données pixel par pixel dans le code natif (C++)
        // pour éviter les distorsions d'image. Notre fonction FFI devra gérer cela.
      });

      // Marque que le streaming est maintenant actif.
      _isStreaming = true;
      log('Flux d\'images démarré.', name: 'CameraService');

    } on CameraException catch (e) {
      log('ERREUR CameraException lors du démarrage du streaming: ${e.code}\n${e.description}', name: 'CameraService');
      _isStreaming = false; // Assure que l'état est correct
    } catch (e) {
      log('ERREUR Inattendue lors du démarrage du streaming: $e', name: 'CameraService');
      _isStreaming = false; // Assure que l'état est correct
    }
  }

  /// Arrête le flux d'images de la caméra.
  Future<void> stopStreaming() async {
    if (!_isInitialized || _controller == null) {
      // Pas besoin d'arrêter si non initialisé
      return;
    }
    if (!_isStreaming) {
      // Pas besoin d'arrêter si déjà arrêté
      return;
    }

    log('Arrêt du flux d\'images...', name: 'CameraService');
    try {
      await _controller!.stopImageStream();
      _isStreaming = false;
      _isProcessingFrame = false; // Réinitialise aussi au cas où
      log('Flux d\'images arrêté.', name: 'CameraService');
    } on CameraException catch (e) {
      log('ERREUR CameraException lors de l\'arrêt du streaming: ${e.code}\n${e.description}', name: 'CameraService');
      // L'état de _isStreaming peut être incertain ici, mais on le laisse à false
      _isStreaming = false;
    } catch (e) {
      log('ERREUR Inattendue lors de l\'arrêt du streaming: $e', name: 'CameraService');
      _isStreaming = false;
    }
  }

  /// Libère les ressources utilisées par le service de caméra.
  ///
  /// Doit être appelée lorsque le service n'est plus nécessaire (par exemple,
  /// dans la méthode `dispose` d'un StatefulWidget) pour libérer la caméra
  /// pour d'autres applications et éviter les fuites de mémoire.
  Future<void> dispose() async {
    log('Libération de CameraService...', name: 'CameraService');
    // Arrête le flux avant de disposer du contrôleur.
    await stopStreaming();

    // Dispose du contrôleur de caméra, ce qui libère la ressource caméra.
    await _controller?.dispose();

    // Réinitialise les états internes.
    _controller = null;
    _cameras = null;
    _selectedCamera = null;
    _isInitialized = false;
    _isStreaming = false;
    _isProcessingFrame = false;
    log('CameraService libéré.', name: 'CameraService');
  }
}