package com.coyotevision.app

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.Image
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.interfaces.Detector
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetector
import java.nio.ByteBuffer
import java.util.concurrent.Executor

/** Pose 18.0.0-beta5 ignores InputImage's coordinate matrix; apply CameraX's matrix ourselves. */
internal class PreviewPoseDetector(private val detector: PoseDetector, private val executor: Executor) : Detector<PreviewPose> {
    override fun getDetectorType(): Int = detector.detectorType

    private fun mapped(task: Task<Pose>, transform: Matrix): Task<PreviewPose> {
        // Snapshot the exact matrix supplied for this image; never use a later frame's transform.
        val values = FloatArray(9).also { transform.getValues(it) }
        return task.continueWith(executor) { completed ->
            val points = completed.result.allPoseLandmarks.associate { landmark ->
                landmark.landmarkType to PreviewLandmark(PreviewPosition(landmark.position.x, landmark.position.y), landmark.inFrameLikelihood)
            }
            PoseCoordinateMapper.map(points, values) ?: throw IllegalStateException("视频坐标变换无效")
        }
    }

    override fun process(image: Image, rotationDegrees: Int, transform: Matrix): Task<PreviewPose> =
        mapped(detector.process(image, rotationDegrees), transform)

    override fun process(image: Image, rotationDegrees: Int): Task<PreviewPose> =
        mapped(detector.process(image, rotationDegrees), Matrix())

    override fun process(bitmap: Bitmap, rotationDegrees: Int): Task<PreviewPose> =
        mapped(detector.process(bitmap, rotationDegrees), Matrix())

    override fun process(buffer: ByteBuffer, width: Int, height: Int, rotationDegrees: Int, format: Int): Task<PreviewPose> =
        mapped(detector.process(buffer, width, height, rotationDegrees, format), Matrix())

    override fun close() { detector.close() }
}
