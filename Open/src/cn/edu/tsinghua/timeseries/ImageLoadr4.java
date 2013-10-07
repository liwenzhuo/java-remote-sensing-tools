/**
 * 
 */
package cn.edu.tsinghua.timeseries;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import javax.media.jai.PlanarImage;
import javax.media.jai.iterator.RandomIter;
import javax.media.jai.iterator.RandomIterFactory;

import org.gdal.gdal.Dataset;
import org.gdal.gdal.gdal;

import cn.edu.tsinghua.lidar.BitChecker;
import cn.edu.tsinghua.modis.BitCheck;

import com.berkenviro.gis.GISUtils;
import com.berkenviro.imageprocessing.GDALUtils;
import com.berkenviro.imageprocessing.ImageData;
import com.berkenviro.imageprocessing.JAIUtils;
import com.vividsolutions.jts.geom.Point;

/**
 * @author Nicholas Clinton
 * @author Cong Hui He
 * 
 * Load a time series of imagery from the MODIS MOD13 product.
 * 20131007. Clean up.  
 */
public class ImageLoadr4 implements Loadr {

	private ArrayList<DatedQCImage> imageList;
	private double[] t;
	private Calendar date0;
	private ImageData[] _image_data;
	private ImageData[] _qc_image_data;
	
	private BitCheck bitChecker;

	/**
	 * 
	 * @param directories
	 * @throws Exception
	 */
	public ImageLoadr4(String[] directories, String dataDir, String qaqcDir, BitCheck bitChecker) throws Exception {
		System.out.println("Initializing image loader...");
		
		this.bitChecker = bitChecker;
		
		gdal.SetConfigOption("GDAL_MAX_DATASET_POOL_SIZE", "50");
		gdal.SetCacheMax(1024);
		
		imageList = new ArrayList<DatedQCImage>();

		for (int d=0; d<directories.length; d++) {
			File dir = new File(directories[d]);
			if (!dir.isDirectory()) {
				throw new Exception("Jackass!  Invalid directory assigned. Directory " + dir.getPath() + " not found.");
			}
			File[] dates = dir.listFiles();
			for (File f : dates) {
				// skip files that may be in the directory
				if (f.isFile()) { continue; }
				// Instantiate a new image Object
				DatedQCImage image = new DatedQCImage();
				// parse the directory name to get a date
				String[] ymd = f.getName().split("\\.");
				Calendar c = Calendar.getInstance();
				c.set(Integer.parseInt(ymd[0]), Integer.parseInt(ymd[1])-1, Integer.parseInt(ymd[2]));
				image.cal = c;
				// find the image in a subdirectory
				File imageDir = new File(f.getPath()+"/"+dataDir);
				for (File iFile : imageDir.listFiles()) {
					if (iFile.getName().endsWith(".tif")) {
						image.imageName = iFile.getAbsolutePath();
					}
				}
				// find the QC image in another subdirectory
				File qcDir = new File(f.getPath()+"/"+qaqcDir);
				for (File qcFile : qcDir.listFiles()) {
					if (qcFile.getName().endsWith(".tif")) {
						image.qcImageName = qcFile.getAbsolutePath();
					}
				}
				//				System.out.println(image);
				imageList.add(image);
			}
		}

		// Keep the list in chronological order
		Collections.sort(imageList);
		System.out.println("\t Done!");

		// reference to the first image, unless otherwise specified
		date0 = imageList.get(0).cal;

		System.out.println("GDAL init...");
		gdal.AllRegister();

		_image_data = new ImageData[imageList.size()];
		_qc_image_data = new ImageData[imageList.size()];

		// set the time vector
		t = new double[imageList.size()];
		for (int i=0; i<imageList.size(); i++) {
			DatedQCImage dImage = imageList.get(i);
			t[i] = diffDays(dImage);

			// set Image Data
			_qc_image_data[i] = new ImageData(dImage.qcImageName, 1);
			_image_data[i] = new ImageData(dImage.imageName, 1);

			//			System.out.println(dImage.imageName);
		}
	}


	/**
	 * Optionally set the zero reference for the time series, i.e. the reference time compared
	 * to which the t-coordinate of the images will be computed.
	 * @param cal
	 */
	public void setDateZero(Calendar cal) {
		date0 = cal;
		// rebuild t
		for (int i=0; i<imageList.size(); i++) {
			DatedQCImage dImage = imageList.get(i);
			t[i] = diffDays(dImage);
		}
	}


	/**
	 * 
	 * @return
	 */
	public DatedQCImage getLast() {
		if (imageList != null) {
			return imageList.get(imageList.size()-1);
		}
		return null;
	} 

	/**
	 * 
	 * @param i
	 * @return
	 */
	public DatedQCImage getI(int i) {
		if (imageList != null) {
			return imageList.get(i);
		}
		return null;
	} 

	/**
	 * Get the overall length of the time series rounded to the nearest number of days.
	 * @return
	 */
	public int getLengthDays() {
		return diffDays(imageList.get(0).cal, getLast().cal);
	}

	/**
	 * 
	 * @param c1
	 * @param c2
	 * @return
	 */
	public static int diffDays(Calendar c1, Calendar c2) {
		long milliseconds1 = c1.getTimeInMillis();
		long milliseconds2 = c2.getTimeInMillis();
		long diff = milliseconds2 - milliseconds1;
		return Math.round(diff / (24 * 60 * 60 * 1000));
	}

	/**
	 * 
	 * @param im1
	 * @param im2
	 * @return
	 */
	public int diffDays(DatedQCImage im2) {
		return diffDays(date0, im2.cal);
	}

	/**
	 * Return size of the list.
	 * @return
	 */
	public int getLengthImages() {
		return imageList.size();
	}

	@Override
	public String toString() {
		String out = "";
		for (int i=0; i<imageList.size(); i++) {
			out+=imageList.get(i)+"\n";
		}
		return out;
	}

	/**
	 * Due to disk read loads, synchonized, so multiple this.getSeries() requests don't occur.
	 * @param pt is a georeferenced Point
	 * @return a list of {t, value} double arrays.
	 */
	public synchronized List<double[]> getSeries(Point pt) {		
		return getSeries(pt.getX(), pt.getY());
	}


	/**
	 * @param x is a georeferenced coordinate
	 * @param y is a georeferenced coordinate
	 * @return a list of {t, value} double arrays.
	 */
	public synchronized List<double[]> getSeries(double x, double y) {		
		LinkedList<double[]> out = new LinkedList<double[]>();

		for (int i=0; i<imageList.size(); i++) {
			try {
				long start = System.nanoTime();
				Dataset dataset = _qc_image_data[i].image();
				int qc = (int)_qc_image_data[i].imageValue(dataset, x, y, 1);
				long stop = System.nanoTime();
				long elapsed = (stop - start);
				//				System.out.println(elapsed);

				//if (!BitChecker.mod13ok(qc)) {
				if (!bitChecker.isOK(qc)) {
//										System.err.println("Bad data: " + qc);
					continue;
				}

				long start2 = System.nanoTime();

				// else, write the time offset and the image data
				double data = _image_data[i].imageValue(_image_data[i].image(), x, y, 1);
				//				double data = 0;
				long stop2 = System.nanoTime();
				long elapsed2 = (stop2 - start2);
				//				System.err.format("%-20d%-20d\n", elapsed, elapsed2);

				out.add(new double[] {t[i], data});
				//				System.err.println(t[i] + '\t' + data);
			} catch (Exception e1) {
				e1.printStackTrace();
			} 
		}
		return out;
	}

	/**
	 * Get a complete X vector for the time series.
	 * @return
	 */
	public double[] getX() {
		return t;
	}

	/**
	 * 
	 */
	public void close() {
		for (int i=0; i<imageList.size(); i++) {
			_image_data[i].image().delete();
			_qc_image_data[i].image().delete();
		}
	}

	/**
	 * Fit a thin plate spline to the series and interpolate missing values.
	 * This will fail if first and/or last values are missing, i.e. there is no extrapolation.
	 * @param pt is a georeferenced point with the same coordinate system as the images.
	 * @return a vector of Y values where missing values are interpolated
	 */
	public synchronized double[] getY(Point pt) throws Exception {
		// get the time series under the point
		List<double[]> series = getSeries(pt);
		if (series.size() == imageList.size()) {
			double[] y = new double[series.size()];
			for (int t=0; t<series.size(); t++) {
				y[t] = series.get(t)[1];
			}
			return y;
		}
		else if (series.size() < 4) {  // not enough points to do an interpolation, 4 is arbitrary
			throw new Exception("Not enough data!  n="+series.size());
		}
		else if (series.get(0)[0] > t[0]) { // do not extrapolate in the beginning
			throw new Exception("Start of series out of range: "+series.get(0)[0]);
		}
		else if (series.get(series.size()-1)[0] < t[t.length-1]) { // do not extrapolate at the end
			throw new Exception("End of series out of range: "+series.get(series.size()-1)[0]);
		}
		double[][] xy = TSUtils.getSeriesAsArray(series);
		// fit a spline to interpolate
		//Spline spline = TSUtils.duchonSpline(xy[0], xy[1]);
		DuchonSplineFunction spline = new DuchonSplineFunction(xy);
		return TSUtils.evaluateSpline(spline, t);
	}

	/**
	 * 
	 */
	public static void smallTest() {
		// 2010, 2011 EVI
		String dir1 = "/home/nick/MOD13A2/2010";
		String dir2 = "/home/nick/MOD13A2/2011";

		try {
			ImageLoadr4 loadr = new ImageLoadr4(
					new String[] {dir2, dir1}, 
					"EVI", "VI_QC", 
					new BitCheck() {
						@Override
						public boolean isOK(int check) {
							return BitChecker.mod13ok(check);
						} 
					}
			);
			//			List<double[]> series = loadr.getSeries(GISUtils.makePoint(-121.0, 38.0));
			Point point = GISUtils.makePoint(-121.0, 38.0);
			long start = System.nanoTime();
			List<double[]> series = loadr.getSeries(point.getX(), point.getY());
			long stop = System.nanoTime();
			long interval = stop - start;

			for (double[] datapoint : series) {
				System.out.println(datapoint[0]+", "+datapoint[1]);
			}
			loadr.close();
			System.out.println("Time used: " + interval / 1000);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * 
	 * @throws Exception
	 */
	public static void largeTest() throws Exception {
		// pixel size
		final double delta = 0.008365178679831331;
		// center of the upper left 
		final double ulx = -179.9815962788889;
		final double uly = 69.99592926598972;
		
		final int width_begin = 10500;
		final int width_end = 13000;
		final int height_begin = 4500;
		final int height_end = 5500;
		String dir1 = "/home/nick/MOD13A2/2010";
		String dir2 = "/home/nick/MOD13A2/2011";

		String reference = "/home/nick/workspace/CLINTON/lib/dfg/land_mask.tif";
		PlanarImage ref = JAIUtils.readImage(reference);
		RandomIter iter = RandomIterFactory.create(ref, null);

		ImageLoadr4 loadr = new ImageLoadr4(
				new String[] {dir2, dir1}, 
				"EVI", "VI_QC", 
				new BitCheck() {
					@Override
					public boolean isOK(int check) {
						return BitChecker.mod13ok(check);
					} 
				}
		);

		/**
		 * Please enumerate the pixel line by line 
		 * (that is, counting from y, and then x)
		 */

		for (int  y = height_begin; y < height_end; y++) {
			for (int x = width_begin; x < width_end; x++) {
				double _x = ulx + x * delta;
				double _y = uly - y * delta;
				if (Math.abs(_y) > 60.0) {
					//	System.out.println("PERSIANN out of bounds.");
					continue; // outside bounds of PERSIANN
				}

				if (iter.getSample(x, y, 0) == 0) {
					//					System.out.println("Not land.");
					continue; // not land
				}

				long start = System.nanoTime();
				List<double[]> series = loadr.getSeries(_x, _y);
				long stop = System.nanoTime();
				double interval = (stop - start) / 1000000.0;
				System.err.println(interval);

				//				for (double[] datapoint : series) {
				//					System.out.println(datapoint[0]+", "+datapoint[1]);
				//				}
			}
		}
	}

	/**
	 * TO-DO: enumerate the pixel line by line rather than column by column
	 * @param args
	 */
	public static void main(String[] args) {
		//		smallTest();
		try {
			largeTest();
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}

}