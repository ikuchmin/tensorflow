package org.tensorflow.demo;

import android.graphics.*;
import android.util.Log;

import static java.lang.Math.PI;

/*
 * pHash-like image hash.
 * Author: Elliot Shepherd (elliot@jarofworms.com
 * Based On: http://www.hackerfactor.com/blog/index.php?/archives/432-Looks-Like-It.html
 */
public class ImagePHash {

    private static final String TAG = "ImagePHASH";
    private int size = 32;
    private int smallerSize = 8;

    private double[][] ph;
    private double[][] transpose;

    public ImagePHash() {
        transpose = transposeMatrix(ph);
        ph = ph_dct_matrix(size);

        initMatrix();
        initCoefficients();
    }

    public ImagePHash(int size, int smallerSize) {
        this.size = size;
        this.smallerSize = smallerSize;

        initMatrix();
        initCoefficients();
    }

    private void initMatrix() {
        transpose = transposeMatrix(ph);
        ph = ph_dct_matrix(size);
    }

    public int distance(String s1, String s2) {
        if (s1 != null && s2 != null) {
            if (s1.length() == s2.length() && s1.length() != 0 && s2.length() != 0) {
                int counter = 0;
                for (int k = 0; k < s1.length(); k++) {
                    if (s1.charAt(k) != s2.charAt(k)) {
                        counter++;
                    }
                }
                Log.d(TAG, "Distance: " + counter + " from " + s1.length());
                return counter;
            } else {
                Log.d(TAG, "Length of strings not equal: s1 = " + s1.length() + " and s2 = " + s2.length() + " or smaller then 0");
                return -1;
            }
        }
        return -1;
    }

    // Returns a 'binary string' (like. 001010111011100010) which is easy to do a hamming distance on.
    public String culcPHash(Bitmap img) {

        /* 1. Reduce size.
         * Like Average Hash, pHash starts with a small image.
         * However, the image is larger than 8x8; 32x32 is a good size.
         * This is really done to simplify the DCT computation and not
         * because it is needed to reduce the high frequencies.
         */
        img = resize(img, size, size);

        /* 2. Reduce color.
         * The image is reduced to a grayscale just to further simplify
         * the number of computations.
         */
        String hash = "";
        if (img != null) {
            img = grayscale(img);

            double[][] vals = new double[size][size];

            for (int x = 0; x < img.getWidth(); x++) {
                for (int y = 0; y < img.getHeight(); y++) {
                    vals[x][y] = getBlue(img, x, y);
                }
            }

        /* 3. Compute the DCT.
         * The DCT separates the image into a collection of frequencies
         * and scalars. While JPEG uses an 8x8 DCT, this algorithm uses
         * a 32x32 DCT.
         */
            long start = System.currentTimeMillis();
            double[][] dctVals = applyDCT(vals);
            Log.d(TAG, String.valueOf((System.currentTimeMillis() - start)));

        /* 4. Reduce the DCT.
         * This is the magic step. While the DCT is 32x32, just keep the
         * top-left 8x8. Those represent the lowest frequencies in the
         * picture.
         */
        /* 5. Compute the average value.
         * Like the Average Hash, compute the mean DCT value (using only
         * the 8x8 DCT low-frequency values and excluding the first term
         * since the DC coefficient can be significantly different from
         * the other values and will throw off the average).
         */
            double total = 0;

            for (int x = 0; x < smallerSize; x++) {
                for (int y = 0; y < smallerSize; y++) {
                    total += dctVals[x][y];
                }
            }
            total -= dctVals[0][0];

            double avg = total / (double) ((smallerSize * smallerSize) - 1);

        /* 6. Further reduce the DCT.
         * This is the magic step. Set the 64 hash bits to 0 or 1
         * depending on whether each of the 64 DCT values is above or
         * below the average value. The result doesn't tell us the
         * actual low frequencies; it just tells us the very-rough
         * relative scale of the frequencies to the mean. The result
         * will not vary as long as the overall structure of the image
         * remains the same; this can survive gamma and color histogram
         * adjustments without a problem.
         */


            for (int x = 0; x < smallerSize; x++) {
                for (int y = 0; y < smallerSize; y++) {
                    if (x != 0 && y != 0) {
                        hash += (dctVals[x][y] > avg ? "1" : "0");
                    }
                }
            }
            Log.d(TAG, "HASH result: " + hash);
        } else {
            return null;
        }
        return hash;
    }

    public Bitmap resize(Bitmap bm, int newHeight, int newWidth) {
        // "RECREATE" THE NEW BITMAP
        Bitmap resizedBitmap = null;
        try {
            resizedBitmap = Bitmap.createScaledBitmap(bm, newWidth, newHeight, false);
        } catch (NullPointerException e) {
            e.printStackTrace();
        }
        return resizedBitmap;
    }

    private Bitmap grayscale(Bitmap orginalBitmap) {
        ColorMatrix colorMatrix = new ColorMatrix();
        colorMatrix.setSaturation(0);

        ColorMatrixColorFilter colorMatrixFilter = new ColorMatrixColorFilter(colorMatrix);

        Bitmap blackAndWhiteBitmap = orginalBitmap.copy(Bitmap.Config.ARGB_8888, true);

        Paint paint = new Paint();
        paint.setColorFilter(colorMatrixFilter);

        Canvas canvas = new Canvas(blackAndWhiteBitmap);
        canvas.drawBitmap(blackAndWhiteBitmap, 0, 0, paint);

        return blackAndWhiteBitmap;
    }

    private static int getBlue(Bitmap img, int x, int y) {
        return (img.getPixel(x, y)) & 0xff;
    }

    // DCT function stolen from http://stackoverflow.com/questions/4240490/problems-with-dct-and-idct-algorithm-in-java

    private double[] c;

    private void initCoefficients() {
        c = new double[size];

        for (int i = 1; i < size; i++) {
            c[i] = 1;
        }
        c[0] = 1 / Math.sqrt(2.0);
    }

//    private double[][] applyDCT(double[][] f) {
//        int N = size;
//
//        double[][] F = new double[N][N];
//        for (int u = 0; u < N; u++) {
//            for (int v = 0; v < N; v++) {
//                double sum = 0.0;
//                for (int i = 0; i < N; i++) {
//                    for (int j = 0; j < N; j++) {
//                        sum += Math.cos(((2 * i + 1) / (2.0 * N)) * u * Math.PI) * Math.cos(((2 * j + 1) / (2.0 * N)) * v * Math.PI) * (f[i][j]);
//                    }
//                }
//
//                sum *= ((c[u] * c[v]) / 4.0);
//                F[u][v] = sum;
//            }
//        }
//        return F;
//    }

    private double[][] applyDCT(double[][] f) {
        return mult(mult(ph, f), transpose);
    }

    public static double[][] mult(double a[][], double b[][]) {//a[m][n], b[n][p]
        if (a.length == 0) return new double[0][0];
        if (a[0].length != b.length) return null; //invalid dims

        int n = a[0].length;
        int m = a.length;
        int p = b[0].length;

        double ans[][] = new double[m][p];

        for (int i = 0; i < m; i++) {
            for (int j = 0; j < p; j++) {
                for (int k = 0; k < n; k++) {
                    ans[i][j] += a[i][k] * b[k][j];
                }
            }
        }
        return ans;
    }

    public static double[][] ph_dct_matrix(int N) {
        final double c1 = Math.sqrt(2.0 / N);

        double[][] F = new double[N][N];
        for (int u = 0; u < N; u++) {
            for (int v = 0; v < N; v++) {
                F[u][v] = c1 * Math.cos((PI / 2 / N) * v * (2 * u + 1));
            }
        }
        return F;
    }

    public static double[][] transposeMatrix(double[][] m) {
        double[][] temp = new double[m[0].length][m.length];
        for (int i = 0; i < m.length; i++)
            for (int j = 0; j < m[0].length; j++)
                temp[j][i] = m[i][j];
        return temp;
    }
}