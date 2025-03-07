/*
 * Copyright (c) 2013-2022, APT Group, Department of Computer Science,
 * The University of Manchester.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package uk.ac.manchester.tornado.benchmarks;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.collections.math.TornadoMath;
import uk.ac.manchester.tornado.api.collections.types.Byte3;
import uk.ac.manchester.tornado.api.collections.types.ImageByte3;
import uk.ac.manchester.tornado.api.collections.types.ImageFloat3;

public class ComputeKernels {

    public static final float S_LOWER_LIMIT = 10.0f;

    public static final float S_UPPER_LIMIT = 100.0f;

    public static final float K_LOWER_LIMIT = 10.0f;

    public static final float K_UPPER_LIMIT = 100.0f;

    public static final float T_LOWER_LIMIT = 1.0f;

    public static final float T_UPPER_LIMIT = 10.0f;

    public static final float R_LOWER_LIMIT = 0.01f;

    public static final float R_UPPER_LIMIT = 0.05f;

    public static final float SIGMA_LOWER_LIMIT = 0.01f;

    public static final float SIGMA_UPPER_LIMIT = 0.10f;

    /**
     * Parallel Implementation of the MonteCarlo computation: this is based on the
     * Marawacc compiler framework.
     *
     * @author Juan Fumero
     */
    public static void monteCarlo(float[] result, int size) {
        final int iter = 25000;
        for (@Parallel int idx = 0; idx < size; idx++) {
            long seed = idx;
            float sum = 0.0f;
            for (int j = 0; j < iter; ++j) {
                // generate a pseudo random number (you do need it twice)
                seed = (seed * 0x5DEECE66DL + 0xBL) & ((1L << 48) - 1);
                seed = (seed * 0x5DEECE66DL + 0xBL) & ((1L << 48) - 1);
                // this generates a number between 0 and 1 (with an awful
                // entropy)
                float x = (seed & 0x0FFFFFFF) / 268435455f;
                // repeat for y
                seed = (seed * 0x5DEECE66DL + 0xBL) & ((1L << 48) - 1);
                seed = (seed * 0x5DEECE66DL + 0xBL) & ((1L << 48) - 1);
                float y = (seed & 0x0FFFFFFF) / 268435455f;
                float dist = TornadoMath.sqrt(x * x + y * y);
                if (dist <= 1.0f) {
                    sum += 1.0f;
                }
            }
            sum = sum * 4;
            result[idx] = sum / iter;
        }
    }

    public static void nBody(int numBodies, float[] refPos, float[] refVel, float delT, float espSqr) {
        for (@Parallel int i = 0; i < numBodies; i++) {
            int body = 4 * i;
            float[] acc = new float[] { 0.0f, 0.0f, 0.0f };
            for (int j = 0; j < numBodies; j++) {
                float[] r = new float[3];
                int index = 4 * j;

                float distSqr = 0.0f;
                for (int k = 0; k < 3; k++) {
                    r[k] = refPos[index + k] - refPos[body + k];
                    distSqr += r[k] * r[k];
                }

                float invDist = 1.0f / TornadoMath.sqrt(distSqr + espSqr);

                float invDistCube = invDist * invDist * invDist;
                float s = refPos[index + 3] * invDistCube;

                for (int k = 0; k < 3; k++) {
                    acc[k] += s * r[k];
                }
            }
            for (int k = 0; k < 3; k++) {
                refPos[body + k] += refVel[body + k] * delT + 0.5f * acc[k] * delT * delT;
                refVel[body + k] += acc[k] * delT;
            }
        }
    }

    /**
     * @param X
     *            input value
     * @brief Abromowitz Stegun approxmimation for PHI (Cumulative Normal
     *        Distribution Function)
     */
    static float phi(final float X) {
        final float c1 = 0.319381530f;
        final float c2 = -0.356563782f;
        final float c3 = 1.781477937f;
        final float c4 = -1.821255978f;
        final float c5 = 1.330274429f;

        final float zero = 0.0f;
        final float one = 1.0f;
        final float two = 2.0f;
        final float temp4 = 0.2316419f;

        final float oneBySqrt2pi = 0.398942280f;

        final float absX = Math.abs(X);
        final float t = one / (one + (temp4 * absX));

        final float y = (one - (oneBySqrt2pi * TornadoMath.exp((-X * X) / two) * t * (c1 + (t * (c2 + (t * (c3 + (t * (c4 + (t * c5))))))))));

        return (X < zero) ? (one - y) : y;
    }

    /*
     * @brief Computes the call and put prices by using Black Scholes model
     *
     * @param randArray input array of random values of current option price
     *
     * @param out output array of calculated put price values
     *
     * @param call output array of calculated call price values
     */
    public static void blackscholes(final float[] randArray, final float[] put, final float[] call) {
        for (@Parallel int gid = 0; gid < call.length; gid++) {
            final float two = 2.0f;
            final float inRand = randArray[gid];
            final float S = (S_LOWER_LIMIT * inRand) + (S_UPPER_LIMIT * (1.0f - inRand));
            final float K = (K_LOWER_LIMIT * inRand) + (K_UPPER_LIMIT * (1.0f - inRand));
            final float T = (T_LOWER_LIMIT * inRand) + (T_UPPER_LIMIT * (1.0f - inRand));
            final float R = (R_LOWER_LIMIT * inRand) + (R_UPPER_LIMIT * (1.0f - inRand));
            final float sigmaVal = (SIGMA_LOWER_LIMIT * inRand) + (SIGMA_UPPER_LIMIT * (1.0f - inRand));

            final float sigmaSqrtT = sigmaVal * TornadoMath.sqrt(T);

            final float d1 = (TornadoMath.log(S / K) + ((R + ((sigmaVal * sigmaVal) / two)) * T)) / sigmaSqrtT;
            final float d2 = d1 - sigmaSqrtT;

            final float KexpMinusRT = K * TornadoMath.exp(-R * T);

            float phiD1 = phi(d1);
            float phiD2 = phi(d2);

            call[gid] = (S * phiD1) - (KexpMinusRT * phiD2);
            phiD1 = phi(-d1);
            phiD2 = phi(-d2);

            put[gid] = (KexpMinusRT * phiD2) - (S * phiD1);
        }
    }

    public static void computeDFT(double[] inreal, double[] inimag, double[] outreal, double[] outimag) {
        int n = inreal.length;
        for (@Parallel int k = 0; k < n; k++) { // For each output element
            double sumReal = 0;
            double simImag = 0;
            for (int t = 0; t < n; t++) { // For each input element
                double angle = (2 * Math.PI * t * k) / n;
                sumReal += inreal[t] * Math.cos(angle) + inimag[t] * Math.sin(angle);
                simImag += -inreal[t] * Math.sin(angle) + inimag[t] * Math.cos(angle);
            }
            outreal[k] = sumReal;
            outimag[k] = simImag;
        }
    }

    /**
     * Parallel Implementation of the Mandelbrot: this is based on the Marawacc
     * compiler framework.
     *
     * @author Juan Fumero
     */
    public static void mandelbrot(int size, short[] output) {
        final int iterations = 10000;
        float space = 2.0f / size;
        for (@Parallel int i = 0; i < size; i++) {
            for (@Parallel int j = 0; j < size; j++) {
                float Zr = 0.0f;
                float Zi = 0.0f;
                float Cr = (1 * j * space - 1.5f);
                float Ci = (1 * i * space - 1.0f);
                float ZrN = 0;
                float ZiN = 0;
                int y = 0;
                for (int ii = 0; ii < iterations; ii++) {
                    if (ZiN + ZrN <= 4.0f) {
                        Zi = 2.0f * Zr * Zi + Ci;
                        Zr = 1 * ZrN - ZiN + Cr;
                        ZiN = Zi * Zi;
                        ZrN = Zr * Zr;
                        y++;
                    } else {
                        ii = iterations;
                    }
                }
                short r = (short) ((y * 255) / iterations);
                output[i * size + j] = r;
            }
        }
    }

    public static void hilbertComputation(float[] output, int rows, int cols) {
        for (@Parallel int i = 0; i < rows; i++) {
            for (@Parallel int j = 0; j < cols; j++) {
                output[i * rows + j] = (float) 1 / (float) ((i + 1) + (j + 1) - 1);
            }
        }
    }

    public static void channelConvolution(int[] channel, int[] channelBlurred, final int numRows, final int numCols, float[] filter, final int filterWidth) {
        // Dealing with an even width filter is trickier
        assert (filterWidth % 2 == 1);
        // For every pixel in the image
        for (@Parallel int r = 0; r < numRows; ++r) {
            for (@Parallel int c = 0; c < numCols; ++c) {
                float result = 0.0f;
                // For every value in the filter around the pixel (c, r)
                for (int filter_r = -filterWidth / 2; filter_r <= filterWidth / 2; ++filter_r) {
                    for (int filter_c = -filterWidth / 2; filter_c <= filterWidth / 2; ++filter_c) {
                        // Find the global image position for this filter
                        // position
                        // clamp to boundary of the image
                        int image_r = Math.min(Math.max(r + filter_r, 0), (numRows - 1));
                        int image_c = Math.min(Math.max(c + filter_c, 0), (numCols - 1));

                        float image_value = (channel[image_r * numCols + image_c]);
                        float filter_value = filter[(filter_r + filterWidth / 2) * filterWidth + filter_c + filterWidth / 2];

                        result += image_value * filter_value;
                    }
                }
                channelBlurred[r * numCols + c] = result > 255 ? 255 : (int) result;
            }
        }
    }

    public static void renderTrack(ImageByte3 output, ImageFloat3 input) {
        for (@Parallel int y = 0; y < input.Y(); y++) {
            for (@Parallel int x = 0; x < input.X(); x++) {
                Byte3 pixel = null;
                final int result = (int) input.get(x, y).getS2();
                switch (result) {
                    case 1: // ok GREY
                        pixel = new Byte3((byte) 128, (byte) 128, (byte) 128);
                        break;
                    case -1: // no input BLACK
                        pixel = new Byte3((byte) 0, (byte) 0, (byte) 0);
                        break;
                    case -2: // not in image RED
                        pixel = new Byte3((byte) 255, (byte) 0, (byte) 0);
                        break;
                    case -3: // no correspondence GREEN
                        pixel = new Byte3((byte) 0, (byte) 255, (byte) 0);
                        break;
                    case -4: // too far away BLUE
                        pixel = new Byte3((byte) 0, (byte) 0, (byte) 255);
                        break;
                    case -5: // wrong normal YELLOW
                        pixel = new Byte3((byte) 255, (byte) 255, (byte) 0);
                        break;
                    default:
                        pixel = new Byte3((byte) 255, (byte) 128, (byte) 128);
                        break;
                }
                output.set(x, y, pixel);
            }
        }
    }

    public static void euler(int size, long[] five, long[] outputA, long[] outputB, long[] outputC, long[] outputD, long[] outputE) {
        for (@Parallel int e = 1; e < five.length; e++) {
            long e5 = five[e];
            for (@Parallel int a = 1; a < five.length; a++) {
                long a5 = five[a];
                for (int b = a; b < size; b++) {
                    long b5 = five[b];
                    for (int c = b; c < size; c++) {
                        long c5 = five[c];
                        for (int d = c; d < size; d++) {
                            long d5 = five[d];
                            if (a5 + b5 + c5 + d5 == e5) {
                                outputA[e] = a;
                                outputB[e] = b;
                                outputC[e] = c;
                                outputD[e] = d;
                                outputE[e] = e;
                            }
                        }
                    }
                }
            }
        }
    }
}