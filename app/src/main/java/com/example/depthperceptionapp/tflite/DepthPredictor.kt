package com.example.depthperceptionapp.tflite

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor // Needed to get quantization parameters
// Uncomment delegates if/when you want to test them:
// import org.tensorflow.lite.gpu.CompatibilityList
// import org.tensorflow.lite.gpu.GpuDelegate
// import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel

/**
 * Manages loading the TFLite MiDaS model and running inference.
 *
 * VERSION ADAPTED FOR UINT8 QUANTIZED MODEL INPUT/OUTPUT.
 * Returns a DEQUANTIZED FloatBuffer (backed by DIRECT memory).
 *
 * Handles:
 * - Loading the TFLite model file from assets.
 * - Initializing the TFLite Interpreter.
 * - Validating model Input/Output types (expects UINT8).
 * - Extracting output dequantization parameters.
 * - Providing a method (`predictDepth`) to run inference on preprocessed UINT8 input data.
 * - Dequantizing the UINT8 output tensor to produce a direct FloatBuffer of relative inverse depth.
 * - Releasing interpreter resources when done (`close`).
 *
 * @param context Android application context, needed to access assets.
 * @throws IOException If the model file cannot be loaded.
 * @throws IllegalArgumentException If the loaded model doesn't have the expected UINT8 input/output types.
 */
class DepthPredictor(context: Context) {

    companion object {
        private const val TAG = "DepthPredictor"
        // --- IMPORTANT: Update this filename if yours is different ---
        private const val MODEL_FILENAME = "midas_v2_1_small_256_uint8.tflite"

        // Expected input dimensions (shape remains the same)
        internal const val INPUT_WIDTH = 256
        internal const val INPUT_HEIGHT = 256
        // Output dimensions (verify with Netron if needed, but assuming 256x256)
        // Made internal so DepthAnalyzer can access them
        internal const val OUTPUT_WIDTH = 256
        internal const val OUTPUT_HEIGHT = 256
    }

    private var interpreter: Interpreter? = null
    var isInitialized: Boolean = false
        private set

    // Store dequantization parameters for the output tensor
    private var outputScale: Float = 1.0f
    private var outputZeroPoint: Int = 0
    private var outputTensorHeight: Int = OUTPUT_HEIGHT // Store actual height after reading model
    private var outputTensorWidth: Int = OUTPUT_WIDTH   // Store actual width after reading model


    init {
        Log.d(TAG, "Initializing DepthPredictor for UINT8 Model...")
        try {
            val modelBuffer = loadModelFile(context, MODEL_FILENAME)
            val interpreterOptions = Interpreter.Options()
            val numThreads = 4 // TUNABLE
            interpreterOptions.setNumThreads(numThreads)
            Log.i(TAG, "Interpreter configured with $numThreads threads.")

            // --- Hardware Acceleration Delegates (Recommended for UINT8) ---
            // Uncomment ONE of these blocks to test NNAPI or GPU acceleration.
            // Test on your target device (Tab S9 FE) to see which gives better performance.
            // NNAPI is often preferred for utilizing NPUs/DSPs.

            /*
            // Option 1: GPU Delegate
             val compatibility = CompatibilityList()
             if (compatibility.isDelegateSupportedOnThisDevice) {
                 Log.i(TAG, "GPU Delegate is supported on this device.")
                 // val gpuDelegateOptions = compatibility.bestOptionsForThisDevice
                 // val gpuDelegate = GpuDelegate(gpuDelegateOptions)
                 // interpreterOptions.addDelegate(gpuDelegate)
                 // Log.i(TAG, "GPU Delegate ADDED (remember to test performance).")
             } else {
                 Log.w(TAG, "GPU Delegate is NOT supported on this device.")
             }
            */

            /*
            // Option 2: NNAPI Delegate (Recommended to try first for quantized models)
             try {
                 val nnApiDelegate = NnApiDelegate() // Consider NnApiDelegate.Options for tuning if needed
                 interpreterOptions.addDelegate(nnApiDelegate)
                 Log.i(TAG, "NNAPI Delegate ADDED (remember to test performance).")
             } catch (e: Exception) {
                 Log.w(TAG, "NNAPI Delegate not available or failed to initialize: ${e.message}")
             }
            */

            interpreter = Interpreter(modelBuffer, interpreterOptions)

            // --- Verify Model Input/Output Types and Get Dequantization Params ---
            validateModelIOTypes() // Throws if types are wrong, sets output dims/params
            logTensorDetails() // Log details including quantization

            isInitialized = true
            Log.i(TAG, "TFLite interpreter loaded successfully for UINT8 model: $MODEL_FILENAME")

        } catch (e: IOException) {
            isInitialized = false
            Log.e(TAG, "IOException loading TFLite model '$MODEL_FILENAME': ${e.message}", e)
            throw e
        } catch (e: IllegalArgumentException) {
            isInitialized = false
            Log.e(TAG, "Model validation failed: ${e.message}", e)
            interpreter?.close()
            interpreter = null
            throw e
        } catch (e: Exception) {
            isInitialized = false
            Log.e(TAG, "Exception initializing TFLite interpreter: ${e.message}", e)
            throw e
        }
    }

    /**
     * Loads the TFLite model file from the application's assets folder using memory mapping.
     */
    @Throws(IOException::class)
    private fun loadModelFile(context: Context, modelName: String): ByteBuffer {
        val assetManager = context.assets
        var assetFileDescriptor: AssetFileDescriptor? = null
        var inputStream: FileInputStream? = null
        var fileChannel: FileChannel? = null
        try {
            assetFileDescriptor = assetManager.openFd(modelName)
            inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
            fileChannel = inputStream.channel
            val startOffset = assetFileDescriptor.startOffset
            val declaredLength = assetFileDescriptor.declaredLength
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        } finally {
            // Ensure resources are closed
            fileChannel?.close()
            inputStream?.close()
            assetFileDescriptor?.close()
        }
    }

    /**
     * Validates that the loaded model has UINT8 input and output tensors,
     * extracts the output dequantization parameters, and stores actual output dimensions.
     * @throws IllegalArgumentException if tensor types or shapes are unexpected.
     */
    private fun validateModelIOTypes() {
        if (interpreter == null) throw IllegalStateException("Interpreter not initialized.")

        // Check Input Tensor
        val inputTensor = interpreter!!.getInputTensor(0)
        if (inputTensor.dataType() != org.tensorflow.lite.DataType.UINT8) {
            throw IllegalArgumentException(
                "Model input tensor requires ${inputTensor.dataType()}, but expected UINT8."
            )
        }
        // Optional: Check input shape if needed
        // val inputShape = inputTensor.shape() // Should be [1, 256, 256, 3]

        // Check Output Tensor and get dequantization parameters
        val outputTensor = interpreter!!.getOutputTensor(0)
        if (outputTensor.dataType() != org.tensorflow.lite.DataType.UINT8) {
            throw IllegalArgumentException(
                "Model output tensor provides ${outputTensor.dataType()}, but expected UINT8."
            )
        }

        // Store actual output dimensions from the model tensor
        val outputShape = outputTensor.shape()
        if (outputShape.size != 4 || outputShape[0] != 1 || outputShape[3] != 1) {
            throw IllegalArgumentException(
                "Unexpected output tensor shape: ${outputShape.joinToString()}. Expected [1, H, W, 1]"
            )
        }
        outputTensorHeight = outputShape[1]
        outputTensorWidth = outputShape[2]
        if(outputTensorHeight != OUTPUT_HEIGHT || outputTensorWidth != OUTPUT_WIDTH) {
            Log.w(TAG, "Model output dimensions (${outputTensorHeight}x${outputTensorWidth}) differ from Companion constants (${OUTPUT_HEIGHT}x${OUTPUT_WIDTH}). Using actual model dimensions.")
            // Update constants isn't possible, but maybe update member variables if needed elsewhere
        }


        // Extract scale and zero point needed to convert UINT8 output back to float
        val quantizationParams = outputTensor.quantizationParams()
        outputScale = quantizationParams.scale
        outputZeroPoint = quantizationParams.zeroPoint
        // Basic sanity check for quantization parameters
        if (outputScale == 0.0f && outputZeroPoint == 0) {
            Log.w(TAG, "Warning: Output tensor quantization scale and zero-point are both zero. Dequantization might be identity or incorrect.")
        } else if (outputScale <= 0f){
            Log.w(TAG, "Warning: Output tensor quantization scale is non-positive ($outputScale). Dequantization might be incorrect.")
        }
        Log.i(TAG, "Output tensor dequantization params - Scale: $outputScale, ZeroPoint: $outputZeroPoint")
    }

    /**
     * Logs details about the loaded model's input and output tensors. Useful for debugging.
     */
    private fun logTensorDetails() {
        if (interpreter == null) return
        try {
            // Input Tensor
            val inputTensor = interpreter!!.getInputTensor(0)
            val inputShape = inputTensor.shape().joinToString()
            val inputType = inputTensor.dataType()
            val inputQuant = inputTensor.quantizationParams() // Log input quant params too
            Log.i(TAG, "Model Input Tensor - Index: 0, Shape: [$inputShape], Type: $inputType, Quant: (Scale:${inputQuant.scale}, ZeroPoint:${inputQuant.zeroPoint})")

            // Output Tensor
            val outputTensor = interpreter!!.getOutputTensor(0)
            val outputShape = outputTensor.shape().joinToString()
            val outputType = outputTensor.dataType()
            val outputQuant = outputTensor.quantizationParams() // Already stored, but log for confirmation
            Log.i(TAG, "Model Output Tensor - Index: 0, Shape: [$outputShape], Type: $outputType, Quant: (Scale:${outputQuant.scale}, ZeroPoint:${outputQuant.zeroPoint})")

        } catch (e: Exception) {
            Log.w(TAG, "Could not log tensor details: ${e.message}")
        }
    }

    /**
     * Runs inference using the UINT8 quantized model.
     * Takes a preprocessed UINT8 input buffer.
     * Outputs a dequantized **direct** FloatBuffer containing the relative inverse depth map.
     *
     * IMPORTANT: This method should ALWAYS be called from a background thread or coroutine.
     *
     * @param inputBuffer The preprocessed input image data as a direct ByteBuffer.
     * Expected format:
     * - Shape: [1, 256, 256, 3] (Batch, Height, Width, Channels)
     * - Type: UINT8 (raw pixel values 0-255)
     * @return A **direct** FloatBuffer containing the DEQUANTIZED relative inverse depth map (disparity).
     * Shape corresponds to model output (e.g., [1, H, W, 1] -> H*W elements). Higher float values = closer.
     * Returns null if the interpreter is not initialized or if inference/dequantization fails.
     */
    fun predictDepth(inputBuffer: ByteBuffer): FloatBuffer? {
        if (!isInitialized || interpreter == null) {
            Log.e(TAG, "Interpreter not initialized. Cannot run inference.")
            return null
        }

        try {
            // --- Prepare UINT8 Output Buffer ---
            // Use the actual dimensions read from the model during init
            val outputElementCount = outputTensorHeight * outputTensorWidth
            val outputBufferSize = outputElementCount * 1 // 1 byte per UINT8 element
            val outputBufferUInt8 = ByteBuffer.allocateDirect(outputBufferSize).order(ByteOrder.nativeOrder())

            // Ensure input buffer is ready
            inputBuffer.rewind()
            outputBufferUInt8.rewind()

            // --- Run Inference ---
            val startTime = System.currentTimeMillis()
            interpreter?.run(inputBuffer, outputBufferUInt8)
            val endTime = System.currentTimeMillis()
            // Log.d(TAG, "Interpreter.run() execution time: ${endTime - startTime} ms") // Verbose

            // --- Dequantize Output to a DIRECT FloatBuffer ---
            outputBufferUInt8.rewind() // Rewind the UINT8 buffer received from interpreter

            // Prepare a DIRECT FloatBuffer for the dequantized results
            val outputFloatByteBufferSize = outputElementCount * 4 // 4 bytes per float
            val outputDirectByteBuffer = ByteBuffer.allocateDirect(outputFloatByteBufferSize).order(ByteOrder.nativeOrder())
            // Create a FloatBuffer view onto the direct ByteBuffer
            val outputBufferFloat = outputDirectByteBuffer.asFloatBuffer()

            // Dequantization formula: float_value = scale * (uint8_value - zero_point)
            for (i in 0 until outputElementCount) {
                // Read the UINT8 value, convert to Int to apply formula correctly
                val quantizedValue = outputBufferUInt8.get().toInt() and 0xFF // Read uint8 as Int (0-255)
                val floatValue = outputScale * (quantizedValue - outputZeroPoint)
                outputBufferFloat.put(floatValue) // Put the dequantized float into the buffer view
            }
            outputBufferFloat.rewind() // Prepare FloatBuffer for reading by AnalysisManager

            // Log min/max of dequantized output for debugging scaling issues
            // logMinMaxFloatBuffer(outputBufferFloat, "Dequantized Output")

            return outputBufferFloat // Return the FloatBuffer view backed by direct memory

        } catch (e: Exception) {
            Log.e(TAG, "Error during TFLite inference or dequantization: ${e.message}", e)
            return null
        }
    }

    /** Helper function to log min/max values in a FloatBuffer (for debugging) */
    private fun logMinMaxFloatBuffer(buffer: FloatBuffer, name: String) {
        if (buffer.remaining() == 0) {
            Log.d(TAG, "$name buffer is empty.")
            return
        }
        var minVal = Float.MAX_VALUE
        var maxVal = Float.MIN_VALUE
        val originalPosition = buffer.position()
        buffer.rewind()
        while (buffer.hasRemaining()) {
            val value = buffer.get()
            if (value < minVal) minVal = value
            if (value > maxVal) maxVal = value
        }
        buffer.position(originalPosition) // Restore original position
        Log.d(TAG, "$name - Min: $minVal, Max: $maxVal")
    }


    /*
     * ==================================================
     * == MiDaS UINT8 Model Output Interpretation ==
     * ==================================================
     * - The predictDepth method handles the UINT8 output from the model.
     * - It performs DEQUANTIZATION using the scale and zero-point extracted from the model.
     * - The returned **direct** FloatBuffer contains the dequantized relative inverse depth values.
     *
     * - KEY POINT: HIGHER float values indicate CLOSER objects (higher disparity).
     * - KEY POINT: LOWER float values indicate FARTHER objects (lower disparity).
     *
     * - CAVEAT: Values are RELATIVE. Conversion to metric distance is deferred. Check the
     * typical range of the dequantized float values (using logs or debugger) to help
     * tune thresholds in AnalysisManager (like obstacleClosenessThreshold).
     */

    // --- [Keep the large comment block about UINT8 model considerations here] ---
    /*
    =====================================================================================
    == Considerations for using this UINT8 MiDaS TFLite Model on Mobile ==
    =====================================================================================
      ... (Full comment block as generated before) ...
    =====================================================================================
    */


    /**
     * Releases the TFLite interpreter and its associated resources.
     */
    fun close() {
        Log.d(TAG, "Closing TFLite interpreter...")
        try {
            interpreter?.close()
            interpreter = null
            isInitialized = false
            Log.i(TAG, "TFLite interpreter closed successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing TFLite interpreter: ${e.message}", e)
        }
    }
}