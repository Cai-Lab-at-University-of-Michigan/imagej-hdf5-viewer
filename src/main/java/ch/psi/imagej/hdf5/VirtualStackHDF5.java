package ch.psi.imagej.hdf5;

import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

import hdf.hdf5lib.H5;
import hdf.object.Dataset;
import hdf.object.h5.H5File;
import ij.IJ;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

public class VirtualStackHDF5 extends BufferedVirtualStack {
	
	
	private static final Logger logger = Logger.getLogger(VirtualStackHDF5.class.getName());
	
	private int bitDepth = 0;
	private Dataset dataset;
	private H5File file;
	private long[] chunkSizes;
	
	public VirtualStackHDF5(H5File file, Dataset dataset){
		// super((int) dataset.getDims()[2], (int) dataset.getDims()[1]);
		this.dataset = dataset;
		this.file = file;
		this.chunkSizes = dataset.getChunkSize();

		String pluginPath = System.getProperty("user.dir") + "/plugins/hdf5-plugin";
		System.out.println("Setting plugin with path: " + pluginPath);
		H5.H5PLappend(pluginPath);
	}
	
	/** Does noting. */
	public void addSlice(String sliceLabel, Object pixels) {
	}

	/** Does nothing.. */
	public void addSlice(String sliceLabel, ImageProcessor ip) {
	}

	/** Does noting. */
	public void addSlice(String sliceLabel, ImageProcessor ip, int n) {
	}

	/** Does noting. */
	public void deleteSlice(int n) {
	}

	/** Does noting. */
	public void deleteLastSlice() {
	}

	public Object getPixels(int slice) {
		try {
			long[] dimensions = dataset.getDims();

			// Select what to readout
			long[] selected = dataset.getSelectedDims();
			if (this.chunkSizes != null) {
				selected[0] = (this.chunkSizes[0] > proc_buffer_MAX) ? proc_buffer_MAX : this.chunkSizes[0];
			} else {
				selected[0] = 2;
			}

			selected[1] = dimensions[1];
			selected[2] = dimensions[2];

			System.out.println("Selected: " + Arrays.toString(selected));

			long[] start = dataset.getStartDims();
			long selectedIndex = selected[0] / 2;
			start[0] = Math.max(slice - selectedIndex - 1, 0); // Indexing at image J starts at 1
			System.out.println("Start: " + Arrays.toString(start));

			Object wholeDataset = dataset.read();
			if (wholeDataset instanceof byte[]) {
				wholeDataset = (byte[]) wholeDataset;
			} else if (wholeDataset instanceof short[]) {
				wholeDataset = (short[]) wholeDataset;
			} else if (wholeDataset instanceof int[]) {
				wholeDataset = HDF5Utilities.convertToFloat((int[]) wholeDataset);
			} else if (wholeDataset instanceof long[]) {
				wholeDataset = HDF5Utilities.convertToFloat((long[]) wholeDataset);
			} else if (wholeDataset instanceof float[]) {
				wholeDataset = (Float[]) wholeDataset;
			} else if (wholeDataset instanceof double[]) {
				wholeDataset = HDF5Utilities.convertToFloat((double[]) wholeDataset);
			} else {
				System.out.println("Unknown stack type");
				throw new IllegalArgumentException("Unknown stack type");
			}

			long sliceSize = selected[1] * selected[2];
			for (int s = 0; s < selected[0]; ++s) {
				System.out.println("Adding to cache: " + (int) (s + start[0] + 1));
				ImageProcessor ip = getProcessor(wholeDataset, dimensions, (int) (s * sliceSize), (int) ((s + 1) * sliceSize));
				addToCache(ip, (int) (s + start[0] + 1));
			}

			System.out.println("Selected index: " + selectedIndex);
			return getArraySubslice(wholeDataset, (int) (selectedIndex * sliceSize), (int) ((selectedIndex + 1) * sliceSize));
		} catch (OutOfMemoryError | Exception e) {
			logger.log(Level.WARNING, "Unable to open slice", e);
		}
		
		return null;
	}

	private Object getArraySubslice(Object arr, int start, int end) {
		if (arr instanceof byte[]) {
			return Arrays.copyOfRange((byte[]) arr, start, end);
		} else if (arr instanceof short[]) {
			return Arrays.copyOfRange((short[]) arr, start, end);
		} else if (arr instanceof int[]) {
			return Arrays.copyOfRange((int[]) arr, start, end);
		} else if (arr instanceof float[]) {
			return Arrays.copyOfRange((float[]) arr, start, end);
		} else {
			throw new IllegalArgumentException("Unknown array type");
		}
	}

	private ImageProcessor getProcessor(Object arr, long[] dimensions, int start, int end) {
		ImageProcessor ip;

		Object pixels = arr;
		if (end != -1) {	
			pixels = getArraySubslice(arr, start, end);
		}
		
		if (pixels instanceof byte[]){
			ip = new ByteProcessor((int) dimensions[2], (int) dimensions[1]);
		}
		else if (pixels instanceof short[]){
			ip = new ShortProcessor((int) dimensions[2], (int) dimensions[1]);
		}
		else if (pixels instanceof int[]){
			ip = new ColorProcessor((int) dimensions[2], (int) dimensions[1]);
		}
		else if (pixels instanceof float[]){
			ip = new FloatProcessor((int) dimensions[2], (int) dimensions[1]);
		}
		else {
			throw new IllegalArgumentException("Unknown stack type");
		}
		
		ip.setPixels(pixels);
		return ip;
	}

	private void addToCache(ImageProcessor ip, int slice) {
		processor_buffer.put(slice, ip);
		processor_fifo.add(slice);
	}

	/**
	 * Assigns a pixel array to the specified slice, were 1<=n<=nslices.
	 */
	public void setPixels(Object pixels, int n) {
	}

	/**
	 * Returns an ImageProcessor for the specified slice, were 1<=n<=nslices.
	 * Returns null if the stack is empty.
	 */
	public ImageProcessor getProcessor(int slice) {
		int t0 = (int) System.currentTimeMillis();
		IJ.log("Getting processor, slice: " + slice);
		test_lock.lock();
        if (processor_fifo.size() > proc_buffer_MAX) {
            int to_remove = processor_fifo.remove();
            IJ.log( "\tClearing Cache Item " + to_remove );
            processor_buffer.remove(to_remove);

            buffer_clean_count++;
            if (buffer_clean_count % 10 == 0) {
                System.gc();
                buffer_clean_count = 0;
            }
        }

        ImageProcessor to_return = null;
        if (processor_buffer.containsKey(slice)) {
            IJ.log("Cache HIT (" + slice + ") " + processor_buffer.size());
            to_return = processor_buffer.get(slice);
        } else {
            IJ.log("Cache MISS (" + slice + ")");
            to_return = getProcessor_internal(slice);;
        }

        this.gui.updateStatus();
        test_lock.unlock();

		IJ.log("Getting processor, slice: " + slice + " took " + ((int) System.currentTimeMillis() - t0) + "ms");

        //return to_return;
        
        if( to_return instanceof ByteProcessor ) {
            ByteProcessor out = new ByteProcessor( to_return.getWidth(), to_return.getHeight(), (byte[]) to_return.getPixels(), to_return.getColorModel() );
            return out; 
        } else if( to_return instanceof ShortProcessor ) {
            ShortProcessor out = new ShortProcessor( to_return.getWidth(), to_return.getHeight(), (short[]) to_return.getPixels(), to_return.getColorModel() );
            return out; 
        } else if( to_return instanceof FloatProcessor ) {
            FloatProcessor out = new FloatProcessor( to_return.getWidth(), to_return.getHeight(), (float[]) to_return.getPixels(), to_return.getColorModel() );
            return out; 
        } else if( to_return instanceof ColorProcessor ) {
            ColorProcessor out = new ColorProcessor( to_return.getWidth(), to_return.getHeight(), (int[]) to_return.getPixels() );
            return out; 
        }

        return to_return;
	}

	public ImageProcessor getProcessor_internal(int n) {
        // IJ.log("Loading Processor " + n + "...");
		System.out.println("Loading Processor " + n + "...");
        if (isOutOfRange(n)) {
            throw new IllegalArgumentException("Argument out of range: " + n);
        }
		
		long[] dimensions = dataset.getDims();
		final Object pixels = getPixels(n);
		
		// Todo support more ImageProcessor types
		ImageProcessor ip = getProcessor(pixels, dimensions, 0, -1);
		
		if (pixels instanceof byte[]){
			ip = new ByteProcessor((int) dimensions[2], (int) dimensions[1]);
		}
		else if (pixels instanceof short[]){
			ip = new ShortProcessor((int) dimensions[2], (int) dimensions[1]);
		}
		else if (pixels instanceof int[]){
			ip = new ColorProcessor((int) dimensions[2], (int) dimensions[1]);
		}
		else if (pixels instanceof float[]){
			ip = new FloatProcessor((int) dimensions[2], (int) dimensions[1]);
		}
		else {
			throw new IllegalArgumentException("Unknown stack type");
		}
		
		ip.setPixels(pixels);

		return ip;
    }

	/** Returns the number of slices in this stack. */
	public int getSize() {
		return (int) this.dataset.getDims()[0];
	}

	/** Returns the label of the Nth image. */
	public String getSliceLabel(int slice) {
		return "Slice: "+slice;
	}

	/** Returns null. */
	public Object[] getImageArray() {
		return null;
	}

	/** Does nothing. */
	public void setSliceLabel(String label, int n) {
	}

	/** Always return true. */
	public boolean isVirtual() {
		return true;
	}

	/** Does nothing. */
	public void trim() {
	}

	/**
	 * Returns the bit depth (8, 16, 24 or 32), or 0 if the bit depth is not
	 * known.
	 */
	public int getBitDepth() {
		return bitDepth;
	}
	
	/**
	 * Close HDF5 file
	 */
	public void close() {		
		logger.info("Closing HDF5 file");
		try{
			file.close();
		}
		catch(Exception e){
			logger.log(Level.WARNING, "Unable to close HDF5 file", e);
		}
		
	}
}
