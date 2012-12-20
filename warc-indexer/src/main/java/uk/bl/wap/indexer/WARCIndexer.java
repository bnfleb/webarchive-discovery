/**
 * 
 */
package uk.bl.wap.indexer;

import static org.archive.io.warc.WARCConstants.HEADER_KEY_TYPE;
import static org.archive.io.warc.WARCConstants.RESPONSE;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Iterator;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerFactoryConfigurationError;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HttpParser;
import org.apache.solr.client.solrj.impl.XMLResponseParser;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.archive.io.ArchiveReader;
import org.archive.io.ArchiveReaderFactory;
import org.archive.io.ArchiveRecord;
import org.archive.io.ArchiveRecordHeader;
import org.archive.io.arc.ARCRecord;
import org.archive.io.warc.WARCRecord;
import org.archive.util.ArchiveUtils;
import org.archive.wayback.util.url.AggressiveUrlCanonicalizer;
import org.w3c.dom.Document;

import uk.bl.wap.util.WARCRecordUtils;
import uk.bl.wap.util.solr.SolrFields;
import uk.bl.wap.util.solr.TikaExtractor;
import uk.bl.wap.util.solr.WctFields;
import uk.bl.wap.util.solr.WritableSolrRecord;

/**
 * @author Andrew Jackson <Andrew.Jackson@bl.uk>
 *
 */
public class WARCIndexer {
	
	TikaExtractor tika = new TikaExtractor();
	MessageDigest md5 = null;
	AggressiveUrlCanonicalizer canon = new AggressiveUrlCanonicalizer();
	SimpleDateFormat formatter = new SimpleDateFormat( "yyyy-MM-dd'T'HH:mm:ss'Z'" );

	public WARCIndexer() throws NoSuchAlgorithmException {
		md5 = MessageDigest.getInstance( "MD5" );
	}

	public WritableSolrRecord extract( ArchiveRecord record ) throws IOException {
		ArchiveRecordHeader header = record.getHeader();
		WritableSolrRecord solr = null;

		if( !header.getHeaderFields().isEmpty() ) {
			if( header.getHeaderFieldKeys().contains( HEADER_KEY_TYPE ) && !header.getHeaderValue( HEADER_KEY_TYPE ).equals( RESPONSE ) ) {
				return null;
			}
			
			if( header.getUrl() == null ) return null;
			
			for( String h : header.getHeaderFields().keySet()) {
				System.out.println("ArchiveHeader: "+h+" -> "+header.getHeaderValue(h));
			}
			
			if( ! record.hasContentHeaders() ) return null;

			String referrer = null;
			if( record instanceof WARCRecord ) {
				Header[] headers = HttpParser.parseHeaders(record, "UTF-8");
				for( Header h : headers ) {
					System.out.println("HttpHeader: "+h.getName()+" -> "+h.getValue());
					if( h.getName().equals("Referer"))
						referrer = h.getValue();
				}
				// Parse payload using Tika:
				solr = tika.extract( WARCRecordUtils.getPayload(record) );
			
			} else if ( record instanceof ARCRecord ) {
				ARCRecord arcr = (ARCRecord) record;
				for( Header h : arcr.getHttpHeaders() ) {
					System.out.println("HttpHeader: "+h.getName()+" -> "+h.getValue());
					if( h.getName().equals("Referer"))
						referrer = h.getValue();
				}
				// Parse payload using Tika:
				arcr.skipHttpHeader();
				solr = tika.extract(arcr);
				
			} else {
				System.err.println("WRONG!");
			}
			

			String waybackDate = ( header.getDate().replaceAll( "[^0-9]", "" ) );

			solr.doc.setField( SolrFields.SOLR_ID, waybackDate + "/" + new String( Base64.encodeBase64( md5.digest( header.getUrl().getBytes( "UTF-8" ) ) ) ) );
			solr.doc.setField( SolrFields.SOLR_DIGEST, header.getDigest() );
			solr.doc.setField( SolrFields.SOLR_URL, header.getUrl() );
			solr.doc.setField( SolrFields.SOLR_DOMAIN, canon.urlStringToKey( header.getUrl() ).split( "/" )[ 0 ] );
			try {
				solr.doc.setField( SolrFields.SOLR_TIMESTAMP, formatter.format( ArchiveUtils.parse14DigitDate( waybackDate ) ) );
			} catch( ParseException p ) {
				p.printStackTrace();
			}
			if( referrer != null )
				solr.doc.setField( SolrFields.SOLR_REFERRER_URI, referrer );
			solr.doc.setField( WctFields.WCT_WAYBACK_DATE, waybackDate );
		}
		return solr;
	}


	/**
	 * 
	 * @param args
	 * @throws NoSuchAlgorithmException
	 * @throws IOException
	 * @throws TransformerException 
	 * @throws TransformerFactoryConfigurationError 
	 */
	public static void main( String[] args ) throws NoSuchAlgorithmException, IOException, TransformerFactoryConfigurationError, TransformerException {
		WARCIndexer windex = new WARCIndexer();
		for( String f : args ) {
			ArchiveReader reader = ArchiveReaderFactory.get(f);
			Iterator<ArchiveRecord> ir = reader.iterator();
			while( ir.hasNext() ) {
				ArchiveRecord rec = ir.next();
				WritableSolrRecord doc = windex.extract(rec);
				if( doc != null ) {
					prettyPrintXML(ClientUtils.toXML(doc.doc));
					break;
				}
				System.out.println(" ---- ---- ");
			}
		}
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
}
