/* Copyright 2015 The TensorFlow Authors. All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
==============================================================================*/

package org.tensorflow.demo;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.media.Image;
import android.media.Image.Plane;
import android.media.ImageReader;
import android.media.ImageReader.OnImageAvailableListener;
import android.os.Handler;
import android.os.Trace;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Date;

import junit.framework.Assert;
import org.tensorflow.demo.env.ImageUtils;
import org.tensorflow.demo.env.Logger;

import static java.lang.String.format;


/**
 * Class that takes in preview frames and converts the image to Bitmaps to process with Tensorflow.
 */
public class TensorFlowImageListener implements OnImageAvailableListener {
  private static final Logger LOGGER = new Logger();

  private static final boolean SAVE_PREVIEW_BITMAP = true;

  // These are the settings for the original v1 Inception model. If you want to
  // use a model that's been produced from the TensorFlow for Poets codelab,
  // you'll need to set IMAGE_SIZE = 299, IMAGE_MEAN = 128, IMAGE_STD = 128,
  // INPUT_NAME = "Mul:0", and OUTPUT_NAME = "final_result:0".
  // You'll also need to update the MODEL_FILE and LABEL_FILE paths to point to
  // the ones you produced.
  private static final int NUM_CLASSES = 1001;
  private static final int INPUT_SIZE = 224;
  private static final int IMAGE_MEAN = 117;
  private static final float IMAGE_STD = 1;
  private static final String INPUT_NAME = "input:0";
  private static final String OUTPUT_NAME = "output:0";

//  private static final String MODEL_FILE = "file:///android_asset/classify_image_graph_def.pb";
//  private static final String LABEL_FILE =
//      "file:///android_asset/imagenet_synset_to_human_label_map.txt";

  private static final String MODEL_FILE = "file:///android_asset/tensorflow_inception_graph.pb";
  private static final String LABEL_FILE =
      "file:///android_asset/imagenet_comp_graph_label_strings.txt";

  private Integer sensorOrientation;

  private final TensorFlowImageClassifier tensorflow = new TensorFlowImageClassifier();

  private int previewWidth = 0;
  private int previewHeight = 0;
  private byte[][] yuvBytes;
  private int[] rgbBytes = null;
  private Bitmap rgbFrameBitmap = null;
  private Bitmap croppedBitmap = null;

  private boolean computing = false;

  private Handler handler;

  private RecognitionScoreView scoreView;
  private byte[] previousImage;

  private ImagePHash imagePHash;

  private String backgroundPHash;
  private String prevPHash;

  private boolean init = false; // FIXME: Temporary hack
  private int skipCounter = 0; // FIXME: Temporary hack
  private int skipBarrier = 100;


  public void initialize(
      final AssetManager assetManager,
      final RecognitionScoreView scoreView,
      final Handler handler,
      final Integer sensorOrientation) {
    Assert.assertNotNull(sensorOrientation);
    try {
      tensorflow.initializeTensorFlow(
        assetManager, MODEL_FILE, LABEL_FILE, NUM_CLASSES, INPUT_SIZE, IMAGE_MEAN, IMAGE_STD,
        INPUT_NAME, OUTPUT_NAME);
    } catch (IOException e) {
      LOGGER.e(e, "Exception!");
    }
    this.scoreView = scoreView;
    this.handler = handler;
    this.sensorOrientation = sensorOrientation;
    this.imagePHash = new ImagePHash();
//    this.backgroundPHash = "1010101010101110101010101010101101101010111010011"; //ikuchmin
//    this.backgroundPHash = "0010101010111001011111010111010101011111110101111"; //mkaskov v2
//    this.prevPHash = "1010101010101110101010101010101101101010111010011";
  }

  private void drawResizedBitmap(final Bitmap src, final Bitmap dst) {
    Assert.assertEquals(dst.getWidth(), dst.getHeight());
    final float minDim = Math.min(src.getWidth(), src.getHeight());

    final Matrix matrix = new Matrix();

    // We only want the center square out of the original rectangle.
    final float translateX = -Math.max(0, (src.getWidth() - minDim) / 2);
    final float translateY = -Math.max(0, (src.getHeight() - minDim) / 2);
    matrix.preTranslate(translateX, translateY);

    final float scaleFactor = dst.getHeight() / minDim;
    matrix.postScale(scaleFactor, scaleFactor);

    // Rotate around the center if necessary.
    if (sensorOrientation != 0) {
      matrix.postTranslate(-dst.getWidth() / 2.0f, -dst.getHeight() / 2.0f);
      matrix.postRotate(sensorOrientation);
      matrix.postTranslate(dst.getWidth() / 2.0f, dst.getHeight() / 2.0f);
    }

    final Canvas canvas = new Canvas(dst);
    canvas.drawBitmap(src, matrix, null);
  }

  @Override
  public void onImageAvailable(final ImageReader reader) {
    Image image = null;
    long startTime;
    try {
      image = reader.acquireLatestImage();

      if (image == null) {
        return;
      }

      if (!init) {
        if (skipCounter++ > skipBarrier) init = true;
        image.close();
        return;
      }

      // No mutex needed as this method is not reentrant.
      if (computing) {
        image.close();
        return;
      }
      computing = true;

      Trace.beginSection("imageAvailable");

      startTime = System.currentTimeMillis();
      final Plane[] planes = image.getPlanes();


      // Initialize the storage bitmaps once when the resolution is known.
      if (previewWidth != image.getWidth() || previewHeight != image.getHeight()) {
        previewWidth = image.getWidth();
        previewHeight = image.getHeight();

        LOGGER.i("Initializing at size %dx%d", previewWidth, previewHeight);
        rgbBytes = new int[previewWidth * previewHeight];
        rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Config.ARGB_8888);
        croppedBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Config.ARGB_8888);

        yuvBytes = new byte[planes.length][];
        for (int i = 0; i < planes.length; ++i) {
          yuvBytes[i] = new byte[planes[i].getBuffer().capacity()];
        }
      }

      for (int i = 0; i < planes.length; ++i) {
        planes[i].getBuffer().get(yuvBytes[i]);
      }

      final int yRowStride = planes[0].getRowStride();
      final int uvRowStride = planes[1].getRowStride();
      final int uvPixelStride = planes[1].getPixelStride();
      ImageUtils.convertYUV420ToARGB8888(
          yuvBytes[0],
          yuvBytes[1],
          yuvBytes[2],
          rgbBytes,
          previewWidth,
          previewHeight,
          yRowStride,
          uvRowStride,
          uvPixelStride,
          false);

      image.close();
    } catch (final Exception e) {
      if (image != null) {
        image.close();
      }
      LOGGER.e(e, "Exception!");
      Trace.endSection();
      return;
    }

    rgbFrameBitmap.setPixels(rgbBytes, 0, previewWidth, 0, 0, previewWidth, previewHeight);

    String currentPHash = imagePHash.culcPHash(rgbFrameBitmap);

    if (this.prevPHash == null) this.prevPHash = currentPHash;
    int distance_prev = imagePHash.distance(prevPHash, currentPHash);

    if (this.backgroundPHash == null) this.backgroundPHash = currentPHash;
    int distance_background = imagePHash.distance(backgroundPHash, currentPHash);

    if (distance_prev < 12) {
      computing = false;
      return;
    }
    prevPHash = currentPHash;

    if (distance_background < 12) {
      computing = false;
      return;
    }

    LOGGER.i("Distance more than 20. From background it %d. From prev image it %d.", distance_background, distance_prev);

    drawResizedBitmap(rgbFrameBitmap, croppedBitmap);

//    if (SAVE_PREVIEW_BITMAP) {
//      ImageUtils.saveBitmap(croppedBitmap);
//    }

    try {
      uploadImage(croppedBitmap);
    } catch (IOException e) {
      LOGGER.e("Image can't be road from camera", e);
    }

//    handler.post(
//        new Runnable() {
//          @Override
//          public void run() {
//            final List<Classifier.Recognition> results = tensorflow.recognizeImage(croppedBitmap);
//
//            LOGGER.v("%d results", results.size());
//            for (final Classifier.Recognition result : results) {
//              LOGGER.v("Result: " + result.getTitle());
//            }
//            scoreView.setResults(results);
            computing = false;
//          }
//        });

    Trace.endSection();
  }

  private void pHash(Bitmap src, Bitmap dst) {
    Assert.assertEquals(dst.getWidth(), dst.getHeight());
    final float minDim = Math.min(src.getWidth(), src.getHeight());

    final Matrix matrix = new Matrix();

    // We only want the center square out of the original rectangle.
    final float translateX = -Math.max(0, (src.getWidth() - minDim) / 2);
    final float translateY = -Math.max(0, (src.getHeight() - minDim) / 2);
    matrix.preTranslate(translateX, translateY);

    final float scaleFactor = dst.getHeight() / minDim;
    matrix.postScale(scaleFactor, scaleFactor);

    // Rotate around the center if necessary.
    if (sensorOrientation != 0) {
      matrix.postTranslate(-dst.getWidth() / 2.0f, -dst.getHeight() / 2.0f);
      matrix.postRotate(sensorOrientation);
      matrix.postTranslate(dst.getWidth() / 2.0f, dst.getHeight() / 2.0f);
    }

    final Canvas canvas = new Canvas(dst);
    canvas.drawBitmap(src, matrix, null);
  }

  private String attachmentName = "file";
  private String attachmentFileName = "png";
  private String crlf = "\r\n";
  private String twoHyphens = "--";
  private String boundary =  "*****";
  /*
   * http://stackoverflow.com/questions/34276466/simple-httpurlconnection-post-file-multipart-form-data-from-android-to-google-bl
   */
  private void uploadImage(Bitmap image) throws IOException {
    URL url = new URL("http://server.puremind.tech:8080/journal/recognition"); //TODO: Replace on string which is ritrieved from Settings app
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    try {
      conn.setDoOutput(true);
      conn.setChunkedStreamingMode(0);
      conn.setRequestMethod("POST");

      conn.setRequestProperty("Content-Type", "multipart/form-data;boundary=" + boundary);

      try (DataOutputStream request = new DataOutputStream(new BufferedOutputStream(conn.getOutputStream()))) {
        request.writeBytes(twoHyphens + boundary + crlf);
        request.writeBytes("Content-Disposition: form-data; name=\"" +
                attachmentName + "\";filename=\"" +
                format("preview-%d.png", new Date().getTime()) + "\"" + crlf);
        request.writeBytes(crlf);

        image.compress(Bitmap.CompressFormat.PNG, 99, request);

        request.writeBytes(crlf);
        request.writeBytes(twoHyphens + boundary +
                twoHyphens + crlf);
        request.flush();
      }

    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      conn.disconnect();
    }
  }
}
