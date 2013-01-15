/**
 * 
 */
package uk.bl.wap.indexer;

import static org.archive.io.warc.WARCConstants.HEADER_KEY_TYPE;
import static org.archive.io.warc.WARCConstants.RESPONSE;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerFactoryConfigurationError;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HttpParser;
import org.apache.commons.httpclient.URI;
import org.apache.commons.io.FileUtils;
import org.apache.http.HttpHeaders;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrInputDocument;
import org.archive.io.ArchiveReader;
import org.archive.io.ArchiveReaderFactory;
import org.archive.io.ArchiveRecord;
import org.archive.io.ArchiveRecordHeader;
import org.archive.io.arc.ARCRecord;
import org.archive.io.warc.WARCConstants;
import org.archive.io.warc.WARCRecord;
import org.archive.util.ArchiveUtils;
import org.archive.wayback.util.url.AggressiveUrlCanonicalizer;

import uk.bl.wap.entities.LinkExtractor;
import uk.bl.wap.util.solr.SolrFields;
import uk.bl.wap.util.solr.SolrWebServer;
import uk.bl.wap.util.solr.TikaExtractor;
import uk.bl.wap.util.solr.WritableSolrRecord;

/**
 * @author Andrew Jackson <Andrew.Jackson@bl.uk>
 *
 */
public class WARCIndexer {
	
	public static final String UPDATE_SOLR_PARM = "--update-solr-server";
	TikaExtractor tika = new TikaExtractor();
	MessageDigest md5 = null;
	AggressiveUrlCanonicalizer canon = new AggressiveUrlCanonicalizer();
	SimpleDateFormat formatter = new SimpleDateFormat( "yyyy-MM-dd'T'HH:mm:ss'Z'" );

	public WARCIndexer() throws NoSuchAlgorithmException {
		md5 = MessageDigest.getInstance( "MD5" );
	}
	
	/**
	 * This extracts metadata and text from the ArchiveRecord and creates a suitable SolrRecord.
	 * 
	 * @param archiveName
	 * @param record
	 * @return
	 * @throws IOException
	 */
	public WritableSolrRecord extract( String archiveName, ArchiveRecord record ) throws IOException {
		ArchiveRecordHeader header = record.getHeader();
		WritableSolrRecord solr = new WritableSolrRecord();

		if( !header.getHeaderFields().isEmpty() ) {
			if( header.getHeaderFieldKeys().contains( HEADER_KEY_TYPE ) && !header.getHeaderValue( HEADER_KEY_TYPE ).equals( RESPONSE ) ) {
				return null;
			}
			
			if( header.getUrl() == null ) return null;
			
			//for( String h : header.getHeaderFields().keySet()) {
			//	System.out.println("ArchiveHeader: "+h+" -> "+header.getHeaderValue(h));
			//}
			
			if( ! record.hasContentHeaders() ) return null;
			
			// Basic headers
			
			// Dates
			String waybackDate = ( header.getDate().replaceAll( "[^0-9]", "" ) );
			solr.doc.setField( SolrFields.WAYBACK_DATE, waybackDate );
			String year = extractYear(header.getDate());
			if( !"0000".equals(year))
				solr.doc.setField( SolrFields.CRAWL_YEAR, year );
			try {
				solr.doc.setField( SolrFields.CRAWL_DATE, formatter.format( ArchiveUtils.parse14DigitDate( waybackDate ) ) );
			} catch( ParseException p ) {
				p.printStackTrace();
			}
			
			
			// 
			byte[] md5digest = md5.digest( header.getUrl().getBytes( "UTF-8" ) );
			String md5hex = new String( Base64.encodeBase64( md5digest ) );
			solr.doc.setField( SolrFields.SOLR_ID, waybackDate + "/" + md5hex);
			solr.doc.setField( SolrFields.ID_LONG, Long.parseLong(waybackDate + "00") + ( (md5digest[1] << 8) + md5digest[0] ) );
			solr.doc.setField( SolrFields.SOLR_DIGEST, header.getHeaderValue(WARCConstants.HEADER_KEY_PAYLOAD_DIGEST) );
			solr.doc.setField( SolrFields.SOLR_URL, header.getUrl() );
			// Spot 'slash pages':
			String[] urlParts = canon.urlStringToKey( header.getUrl() ).split( "/" );
			if( urlParts.length == 1 || (urlParts.length == 2 && urlParts[1].startsWith("index") ) ) 
				solr.doc.setField( SolrFields.SOLR_URL_TYPE, SolrFields.SOLR_URL_TYPE_SLASHPAGE );
			// Record the domain (strictly, the host): 
			String domain = urlParts[ 0 ];
			solr.doc.setField( SolrFields.SOLR_DOMAIN, domain );
			solr.doc.setField( SolrFields.PUBLIC_SUFFIX, LinkExtractor.extractPublicSuffixFromHost(domain) );

			// Parse HTTP headers:
			// TODO Add X-Powered-By, Server as generators? Maybe Server as served_by? Or just server?
			String referrer = null;
			InputStream tikainput = null;
			String statusCode = null;
			String serverType = null;
			if( record instanceof WARCRecord ) {
				String firstLine[] = HttpParser.readLine(record, "UTF-8").split(" ");
				statusCode = firstLine[1];
				Header[] headers = HttpParser.parseHeaders(record, "UTF-8");
				for( Header h : headers ) {
					//System.out.println("HttpHeader: "+h.getName()+" -> "+h.getValue());
					// FIXME This can't work, because the Referer is in the Request, not the Response.
					// TODO Generally, need to think about ensuring the request and response are brought together.
					if( h.getName().equals(HttpHeaders.REFERER))
						referrer = h.getValue();
					if( h.getName().equals(HttpHeaders.CONTENT_TYPE))
						serverType = h.getValue();
				}
				// No need for this, as the headers have already been read from the InputStream:
				// WARCRecordUtils.getPayload(record);
				tikainput = record;
			
			} else if ( record instanceof ARCRecord ) {
				ARCRecord arcr = (ARCRecord) record;
				statusCode = ""+arcr.getStatusCode();
				for( Header h : arcr.getHttpHeaders() ) {
					//System.out.println("HttpHeader: "+h.getName()+" -> "+h.getValue());
					if( h.getName().equals(HttpHeaders.REFERER))
						referrer = h.getValue();
					if( h.getName().equals(HttpHeaders.CONTENT_TYPE))
						serverType = h.getValue();
				}
				arcr.skipHttpHeader();
				tikainput = arcr;
				
			} else {
				System.err.println("FAIL! Unsupported archive record type.");
				return solr;
			}
			
			// Fields from Http headers:
			solr.addField( SolrFields.SOLR_REFERRER_URI, referrer );
			// Get the type from the server
			solr.addField(SolrFields.CONTENT_TYPE_SERVED, serverType);			
			
			// Skip recording non-content URLs (i.e. 2xx responses only please):
			if( statusCode == null || !statusCode.startsWith("2") ) return null;
			
			// Parse payload using Tika:
			
			// Mark the start of the payload, and then run Tika on it:
			tikainput = new BufferedInputStream( tikainput );
			tikainput.mark((int) header.getLength());
			solr = tika.extract( solr, tikainput, header.getUrl() );
			// Derive normalised/simplified content type:
			processContentType(solr, header, serverType);
			
			// Pass on to other extractors as required, resetting the stream before each:
			//tikainput.reset();
			// Entropy, compressibility, fussy hashes, etc.
			// JSoup link extractor for (x)html, deposit in 'links' field.
			
			// These extractors don't need to re-read the payload:
			// Postcode Extractor (based on text extracted by Tika)
			// Named entity detection
			// WctEnricher, currently invoked in the reduce stage to lower query hits.
			
		}
		return solr;
	}
	
	/**
	 * 
	 * @param timestamp
	 * @return
	 */
	public static String extractYear(String timestamp) {
		String waybackYear = "unknown-year";
		String waybackDate = timestamp.replaceAll( "[^0-9]", "" );
		if( waybackDate != null ) 
			waybackYear = waybackDate.substring(0,4);
		return waybackYear;
	}
	
	/**
	 * 
	 * @param url
	 * @return
	 */
	public static String extractHost(String url) {
		String host = "unknown.host";
		URI uri = null;
		// Attempt to parse:
		try {
			uri = new URI(url,false);
			// Extract domain:
			host = uri.getHost();
		} catch ( Exception e ) {
			// Return a special hostname if parsing failed:
			host = "malformed.host";
		}
		return host;
	}
	
	private void processContentType( WritableSolrRecord solr, ArchiveRecordHeader header, String serverType ) {
		StringBuilder mime = new StringBuilder();
		mime.append( ( ( String ) solr.doc.getFieldValue( SolrFields.SOLR_CONTENT_TYPE ) ) );
		if( mime.toString().isEmpty() ) {
			if( header.getHeaderFieldKeys().contains( "WARC-Identified-Payload-Type" ) ) {
				mime.append( ( ( String ) header.getHeaderFields().get( "WARC-Identified-Payload-Type" ) ) );
			} else {
				mime.append( header.getMimetype() );
			}
		}
		
		// Determine content type:
		String contentType = mime.toString();
		solr.doc.setField( SolrFields.FULL_CONTENT_TYPE, contentType );
		
		// Fall back on serverType for plain text:
		if( contentType != null && contentType.startsWith("text/plain")){
			if( serverType != null ){
				contentType = serverType;
			}
		}
		
		// Strip parameters out of main type field:
		solr.doc.setField( SolrFields.SOLR_CONTENT_TYPE, contentType.replaceAll( ";.*$", "" ) );

		// Also add a more general, simplified type, as appropriate:
		// FIXME clean up this messy code:
		if( mime.toString().matches( "^image/.*$" ) ) {
			solr.doc.setField( SolrFields.SOLR_NORMALISED_CONTENT_TYPE, "image" );
		} else if( mime.toString().matches( "^(audio|video)/.*$" ) ) {
			solr.doc.setField( SolrFields.SOLR_NORMALISED_CONTENT_TYPE, "media" );
		} else if( mime.toString().matches( "^text/htm.*$" ) ) {
			solr.doc.setField( SolrFields.SOLR_NORMALISED_CONTENT_TYPE, "html" );
		} else if( mime.toString().matches( "^application/pdf.*$" ) ) {
			solr.doc.setField( SolrFields.SOLR_NORMALISED_CONTENT_TYPE, "pdf" );
		} else if( mime.toString().matches( "^.*word$" ) ) {
			solr.doc.setField( SolrFields.SOLR_NORMALISED_CONTENT_TYPE, "word" );
		} else if( mime.toString().matches( "^.*excel$" ) ) {
			solr.doc.setField( SolrFields.SOLR_NORMALISED_CONTENT_TYPE, "excel" );
		} else if( mime.toString().matches( "^.*powerpoint$" ) ) {
			solr.doc.setField( SolrFields.SOLR_NORMALISED_CONTENT_TYPE, "powerpoint" );
		} else if( mime.toString().matches( "^text/plain.*$" ) ) {
			solr.doc.setField( SolrFields.SOLR_NORMALISED_CONTENT_TYPE, "text" );
		} else {
			solr.doc.setField( SolrFields.SOLR_NORMALISED_CONTENT_TYPE, "other" );
		}
		
		// Remove text from JavaScript, CSS, ...
		if( contentType.startsWith("application/javascript") || 
				contentType.startsWith("text/javascript") || 
				contentType.startsWith("text/css") ){
			solr.doc.removeField(SolrFields.SOLR_EXTRACTED_TEXT);
		}
	}

	
	/**
	 * 
	 * @param args
	 * @throws NoSuchAlgorithmException
	 * @throws IOException
	 * @throws TransformerException 
	 * @throws TransformerFactoryConfigurationError 
	 * @throws SolrServerException 
	 */
	public static void main( String[] args ) throws NoSuchAlgorithmException, IOException, TransformerFactoryConfigurationError, TransformerException, SolrServerException {
		
		if( !( args.length > 1 ) ) {
			System.out.println( "Arguments required are 1) Output directory 2) List of WARC files 3) Optionally --update-solr-server=url" );
			System.exit( 0 );

		}

		String outputDir = args[0];
		if(outputDir.endsWith("/")||outputDir.endsWith("\\")){
			outputDir = outputDir.substring(0, outputDir.length()-1);
		}
		
		outputDir = outputDir + "//";
		System.out.println("Output Directory is: " + outputDir);
		File dir = new File(outputDir);
		if(!dir.exists()){
			FileUtils.forceMkdir(dir);
		}
		
		parseWarcFiles(outputDir, args);
	
	
	}
	
	/**
	 * @param outputDir
	 * @param args
	 * @throws NoSuchAlgorithmException
	 * @throws MalformedURLException
	 * @throws IOException
	 * @throws TransformerFactoryConfigurationError
	 * @throws TransformerException
	 */
	public static void parseWarcFiles(String outputDir, String[] args) throws NoSuchAlgorithmException, MalformedURLException, IOException, TransformerFactoryConfigurationError, TransformerException, SolrServerException{
		
		WARCIndexer windex = new WARCIndexer();
		int argCount = 0;
		
		List<SolrInputDocument> docs = new ArrayList<SolrInputDocument>();
		String solrUrl = null;
				
		// Loop through each Warc file
		argLoop:for( String inputFile : args ) {
			if(argCount == 0){
				// Skip the output directory arg
				argCount++;
				continue argLoop;
			}
			
			// If the Update Solr paramter is used then extract the URL
			if(inputFile.startsWith(UPDATE_SOLR_PARM)){
				String[] strParts = inputFile.split("=");
				String url = strParts[1];
				if(url.contains("\"")){
					url  = url.replaceAll("\"", "");
				}
				solrUrl = new String(url);
				continue argLoop;
			}
			
			ArchiveReader reader = ArchiveReaderFactory.get(inputFile);
			Iterator<ArchiveRecord> ir = reader.iterator();
			int recordCount = 1;
			
			// Iterate though each record in the WARC file
			while( ir.hasNext() ) {
				ArchiveRecord rec = ir.next();
				WritableSolrRecord doc = windex.extract("",rec);
				if( doc != null ) {
					File fileOutput = new File(outputDir + "FILE_" + argCount + "_" + recordCount + ".xml");
					// Write XML to file if not posting straight to the server.
					if(solrUrl == null) {
						writeXMLToFile(doc.toXml(), fileOutput);
					}
					recordCount++;
					
					docs.add(doc.doc);
				}
				
			}
			argCount++;
		}
		
		// If the Solr URL is set then update it with the Docs
		if(solrUrl != null) {		
			SolrWebServer solrWeb = new SolrWebServer(solrUrl);
			solrWeb.updateSolr(docs);
		}
		
		System.out.println("WARC Indexer Finished");
	}
	
	
	public static void prettyPrintXML( String doc ) throws TransformerFactoryConfigurationError, TransformerException {
		Transformer transformer = TransformerFactory.newInstance().newTransformer();
		transformer.setOutputProperty(OutputKeys.INDENT, "yes");
		//initialize StreamResult with File object to save to file
		StreamResult result = new StreamResult(new StringWriter());
		StreamSource source = new StreamSource(new StringReader(doc));
		transformer.transform(source, result);
		String xmlString = result.getWriter().toString();
		System.out.println(xmlString);		
	}
	
	/**
	 * @param xml
	 * @param file
	 * @throws IOException
	 * @throws TransformerFactoryConfigurationError
	 * @throws TransformerException
	 */
	public static void writeXMLToFile( String xml, File file ) throws IOException, TransformerFactoryConfigurationError, TransformerException {
		
		Result result = new StreamResult(file);
		Source source =  new StreamSource(new StringReader(xml));
		
		Transformer transformer = TransformerFactory.newInstance().newTransformer();
		transformer.setOutputProperty(OutputKeys.INDENT, "yes");
		//FileUtils.writeStringToFile(file, xml);
		
		transformer.transform(source, result);
	  
	}
}
