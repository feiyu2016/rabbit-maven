package rabbit.handler;

import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.ImageWriteParam;
import javax.imageio.plugins.jpeg.JPEGImageWriteParam;
import javax.imageio.IIOImage;
import javax.imageio.stream.ImageOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import rabbit.http.HttpHeader;
import rabbit.httpio.ResourceSource;
import rabbit.io.BufferHandle;
import rabbit.proxy.Connection;
import rabbit.proxy.TrafficLoggerHandler;
import rabbit.util.SProperties;

/** This image handler uses standard java imageio to convert images
 *  into low quality jpegs.
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
public class JavaImageHandler extends ImageHandlerBase {
    private static final String STD_QUALITY = "0.1";

    /** For creating the factory.
     */
    public JavaImageHandler () {
    }

    /** Create a new ImageHandler for the given request.
     * @param con the Connection handling the request.
     * @param request the actual request made.
     * @param clientHandle the client side buffer.
     * @param response the actual response.
     * @param content the resource.
     * @param mayCache May we cache this request?
     * @param mayFilter May we filter this request?
     * @param size the size of the data beeing handled.
     */
    public JavaImageHandler (Connection con, TrafficLoggerHandler tlh,
			     HttpHeader request, BufferHandle clientHandle,
			     HttpHeader response, ResourceSource content,
			     boolean mayCache, boolean mayFilter, long size,
			     SProperties config, boolean doConvert,
			     int minSizeToConvert) {
	super (con, tlh, request, clientHandle, response, content,
	       mayCache, mayFilter, size, config, doConvert, minSizeToConvert);
    }

    @Override
    public Handler getNewInstance (Connection con, TrafficLoggerHandler tlh,
				   HttpHeader header, BufferHandle bufHandle,
				   HttpHeader webHeader,
				   ResourceSource content, boolean mayCache,
				   boolean mayFilter, long size) {
	return new JavaImageHandler (con, tlh, header, bufHandle, webHeader,
				     content, mayCache, mayFilter, size,
				     getConfig (), getDoConvert (), 
				     getMinSizeToConvert ());
    }

    private float getQuality () {
	String sq = getConfig ().getProperty ("quality", STD_QUALITY);
	return Float.parseFloat (sq);
    }

    private ImageWriter getImageWriter () throws IOException {
	Iterator<ImageWriter> iter = 
	    ImageIO.getImageWritersByFormatName ("jpeg");
	if (iter.hasNext()) 
	    return iter.next ();
	throw new IOException ("Failed to find jpeg writer");
    }

    private JPEGImageWriteParam getParams () {
	JPEGImageWriteParam iwparam = 
	    new JPEGImageWriteParam (Locale.getDefault ());
	iwparam.setCompressionMode (ImageWriteParam.MODE_EXPLICIT);
	iwparam.setCompressionQuality (getQuality ());
	return iwparam;
    }
    
    @Override protected ImageConversionResult 
    internalConvertImage (String entryName) throws IOException {
	long origSize = size;
	BufferedImage origImage = ImageIO.read (new File (entryName));
	File output = new File (entryName + ".c");
	
	// TODO: check image size so that we can limit total memory usage
	try {
	    ImageWriter writer = getImageWriter ();
	    try {
		ImageOutputStream ios = ImageIO.createImageOutputStream (output);
		try {
		    writer.setOutput(ios);
		    IIOImage iioimage = new IIOImage (origImage, null, null);
		    writer.write (null, iioimage, getParams ());
		} finally {
		    ios.close ();
		}
	    } finally {
		writer.dispose ();
	    }
	} finally {
	    origImage.flush ();
	}
	long lowQualitySize = output.length ();
	try {
	    ImageConversionResult icr = 
		new ImageConversionResult (origSize, lowQualitySize);
	    convertedFile = output;
	    ImageSelector is = new ImageSelector (convertedFile, null);
	    selectImage (entryName, is, icr);
	    convertedFile = is.convertedFile;
	} finally {
	    if (convertedFile != null)
		deleteFile (convertedFile);
	}
	return new ImageConversionResult (origSize, lowQualitySize);
    }

    @Override public void setup (SProperties prop) {
	super.setup (prop);
    }
}