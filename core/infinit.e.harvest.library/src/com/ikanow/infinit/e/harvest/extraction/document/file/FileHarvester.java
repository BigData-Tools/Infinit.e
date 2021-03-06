/*******************************************************************************
 * Copyright 2012, The Infinit.e Open Source Project.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package com.ikanow.infinit.e.harvest.extraction.document.file;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import javax.xml.stream.FactoryConfigurationError;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import jcifs.smb.NtlmPasswordAuthentication;
import jcifs.smb.SmbException;

import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.bson.types.ObjectId;
import org.apache.commons.codec.digest.DigestUtils;

import com.google.gson.stream.JsonReader;
import com.ikanow.infinit.e.data_model.InfiniteEnums;
import com.ikanow.infinit.e.data_model.InfiniteEnums.ExtractorSourceLevelMajorException;
import com.ikanow.infinit.e.data_model.InfiniteEnums.HarvestEnum;
import com.ikanow.infinit.e.data_model.store.config.source.SourceFileConfigPojo;
import com.ikanow.infinit.e.data_model.store.config.source.SourcePojo;
import com.ikanow.infinit.e.data_model.store.document.DocumentPojo;
import com.ikanow.infinit.e.data_model.store.document.GeoPojo;
import com.ikanow.infinit.e.harvest.HarvestContext;
import com.ikanow.infinit.e.harvest.extraction.document.HarvesterInterface;
import com.ikanow.infinit.e.harvest.extraction.document.DuplicateManager;
import com.ikanow.infinit.e.harvest.utils.AuthUtils;
import com.ikanow.infinit.e.harvest.utils.HarvestExceptionUtils;
import com.ikanow.infinit.e.harvest.utils.PropertiesManager;

public class FileHarvester implements HarvesterInterface {

	@SuppressWarnings("unused")
	private static final byte[] SP = "                                              ".getBytes();
	private int maxDepth;
	private Set<Integer> sourceTypesCanHarvest = new HashSet<Integer>();
	private int maxDocsPerCycle = Integer.MAX_VALUE;

	@SuppressWarnings("unused")
	private static final String TYPES[] = {
		"TYPE_COMM",
		"TYPE_FILESYSTEM",
		"TYPE_NAMED_PIPE",
		"TYPE_PRINTER",
		"TYPE_SERVER",
		"TYPE_SHARE",
		"TYPE_WORKGROUP"
	};

	private int errors = 0;
	
	// List of Feeds
	private List<DocumentPojo> files = null;
	private List<DocumentPojo> docsToAdd = null;
	private List<DocumentPojo> docsToUpdate = null;
	private List<DocumentPojo> docsToRemove = null;

	private HashSet<String> sourceUrlsGettingUpdated = null; 
		// (only need to start filling this if I'm persisting metadata across updates)
	
	private HarvestContext _context;
	
	// Can specify regexes to select which files to ignore
	private Pattern includeRegex = null; // files only
	private Pattern excludeRegex = null; // files and paths
	
	/**
	 * Get a specific doc to return the bytes for
	 * @throws Exception 
	 */
	public static byte[] getFile(String fileURL, SourcePojo source ) throws Exception
	{
		InputStream in = null;
		try 
		{
			InfiniteFile searchFile = searchFileShare( source, fileURL);
			
			if ( searchFile == null )
				return null;
			else
			{
				//found the file, return the bytes
				in = searchFile.getInputStream();
				ByteArrayOutputStream buffer = new ByteArrayOutputStream();
				
				int read;
				byte[] data = new byte[16384];
				while ( (read = in.read(data, 0, data.length)) != -1 )
				{
					buffer.write(data,0,read);
				}
				buffer.flush();
				return buffer.toByteArray();
			}			
		} 
		catch (Exception e) 
		{
			throw e;
		}
		finally {
			if (null != in) {
				in.close();
			}
		}
	}
	
	/**
	 * Same as the traverse method but returns the InfiniteFile if it finds searchFile
	 * returns null otherwise
	 * 
	 * @param f
	 * @param source
	 * @param depth
	 * @param searchFile
	 * @return
	 * @throws SmbException
	 */
	
	private static InfiniteFile searchFileShare( SourcePojo source, String searchFile ) throws Exception 
	{
		//TODO (INF-1406): made this synchronized to work around what looks like deadlock issue in code
		// This is v undesirable and should be fixed once the underlying bug has been fixed
		// (note in practice this is only an issue for multiple threads going to the same domain)
		InfiniteFile f;
		synchronized (FileHarvester.class) {
			try {
				if( source.getFileConfig() == null || source.getFileConfig().domain == null || source.getFileConfig().password == null || source.getFileConfig().username == null)
				{
					f = InfiniteFile.create(searchFile);
				}
				else
				{
					if (source.getFileConfig().domain == null) {
						source.getFileConfig().domain = "";
					}
					NtlmPasswordAuthentication auth = new NtlmPasswordAuthentication(source.getFileConfig().domain, source.getFileConfig().username, source.getFileConfig().password);
					f = InfiniteFile.create(searchFile, auth);
				}
			}//TESTED
			catch (Exception e) {
	
				int nIndex = searchFile.lastIndexOf("/");
				searchFile = searchFile.substring(0, nIndex); // (ie not including the /)
				f = searchFileShare(source, searchFile);
				if (f.isDirectory()) {
					throw new MalformedURLException(searchFile + " is directory.");				
				}
			}//TESTED			
			return f;
		}
		// (End INF-1406 sync bug, see above explanation)
	} //TESTED
	
	
	/**
	 * Get the list of docs
	 * @return
	 * @throws Exception 
	 */
	private List<DocumentPojo> getFiles(SourcePojo source) throws Exception {
		InfiniteFile file = null;
		try 
		{
			if( source.getFileConfig() == null || source.getFileConfig().password == null || source.getFileConfig().username == null)
			{
				file = InfiniteFile.create(source.getUrl());
			}
			else
			{
				if (source.getFileConfig().domain == null) {
					source.getFileConfig().domain = "";
				}
				NtlmPasswordAuthentication auth = new NtlmPasswordAuthentication(source.getFileConfig().domain, source.getFileConfig().username, source.getFileConfig().password);
				file = InfiniteFile.create(source.getUrl(), auth);
			}
			if (file.isLocal()) { // Local files can only be harvested by admin users: check this...
				if (!AuthUtils.isAdmin(source.getOwnerId())) {
					throw new ExtractorSourceLevelMajorException("Permission denied");
				}
			}//TESTED
			traverse(file, source, maxDepth);
		} 
		catch (Exception e) {
			// If an exception here this is catastrophic, throw it upwards:
			errors++;
			throw e;
		}
		
		return files;
	}

	/**
	 * Constructor for processing doc information for a source
	 * @param maxDepth
	 */
	public FileHarvester()
	{
		sourceTypesCanHarvest.add(InfiniteEnums.FILES);
		maxDepth = 5;

		PropertiesManager pm = new PropertiesManager();
		maxDocsPerCycle = pm.getMaxDocsPerSource();
	}

	// Process the doc
	private void processFiles(SourcePojo source) throws Exception {

		sourceUrlsGettingUpdated = new HashSet<String>();
		LinkedList<String> duplicateSources = new LinkedList<String>(); 		
		try {			
			// Compile regexes if they are present
			if ((null != source.getFileConfig()) && (null != source.getFileConfig().pathInclude)) {
				includeRegex = Pattern.compile(source.getFileConfig().pathInclude, Pattern.CASE_INSENSITIVE);
			}
			if ((null != source.getFileConfig()) && (null != source.getFileConfig().pathExclude)) {
				excludeRegex = Pattern.compile(source.getFileConfig().pathExclude, Pattern.CASE_INSENSITIVE);				
			}
			
			// Process the fileshare
			getFiles(source);	
		}
		catch (Exception e) {
			// If an exception here this is catastrophic, throw it upwards:
			errors++;
			throw e;
		}

		try {			
			//Dedup code, ironically enough partly duplicated in parse(), probably unnecessarily
			DuplicateManager qr = _context.getDuplicateManager();
			for(DocumentPojo doc: files)
			{
				try {			
					duplicateSources.clear();
					if (null != doc.getSourceUrl()) { 
						
						// For XML files we delete everything that already exists (via docsToRemove) and then add new docs
						docsToAdd.add(doc);	

						// However still need to check for duplicates so can update entities correctly
						// (though obviously only if the the sourceUrl is not new...)
						
						if (sourceUrlsGettingUpdated.contains(doc.getSourceUrl()))
						{
							if (qr.isDuplicate_Url(doc.getUrl(), source, duplicateSources)) {
								doc.setUpdateId(qr.getLastDuplicateId()); // (set _id to doc we're going to replace)								
									// (still don't add this to updates because we've added the source URL to the delete list)
							}
						}
						//TESTED
					}
					else if (qr.isDuplicate_Url(doc.getUrl(), source, duplicateSources)) {
						// Other files, if the file already exists then update it (essentially, delete/add)
						doc.setUpdateId(qr.getLastDuplicateId()); // (set _id to doc we're going to replace)
						docsToUpdate.add(doc);
					}
					else {
						if (!duplicateSources.isEmpty()) {
							doc.setDuplicateFrom(duplicateSources.getFirst());
						}
						docsToAdd.add(doc);
					}
				}
				catch (Exception e) {
					errors++;
					_context.getHarvestStatus().logMessage(HarvestExceptionUtils.createExceptionMessage(e).toString(), true);
				}
			}
		}
		catch (Exception e) {
			// If an exception here this is catastrophic, throw it upwards:
			errors++;
			throw e;
		}
	}

	private void parse( InfiniteFile f, SourcePojo source ) throws MalformedURLException, URISyntaxException {

		DocumentPojo doc = null;		
		//Determine File Extension
		String fileName = f.getName().toString();
		int mid= fileName.lastIndexOf(".");
		String extension = fileName.substring(mid+1,fileName.length()); 

		//Checked to save processing time
		long fileTimestamp = (f.getDate()/1000)*1000;
			// (ensure truncated to seconds, since some operation somewhere hear does this...)
		
		Date modDate = new Date(fileTimestamp);
		//XML Data gets placed into MetaData
		
		boolean bIsXml = false;
		boolean bIsJson = false;
		boolean bIsLineOriented = false;
		if ((null != source.getFileConfig()) && (null != source.getFileConfig().type)) {
			extension = source.getFileConfig().type;
		}
		bIsXml = extension.equalsIgnoreCase("xml");
		bIsJson = extension.equalsIgnoreCase("json");
		bIsLineOriented = extension.endsWith("sv");
		
		if (bIsXml || bIsJson || bIsLineOriented)
		{
			//fast check to see if the file has changed before processing (or if it never existed)
			if(needsUpdated_SourceUrl(modDate, f.getUrlString(), source))
			{
				DocumentPojo docRepresentingSrcUrl = new DocumentPojo();
				docRepresentingSrcUrl.setSourceUrl(f.getUrlString());
				if (null != sourceUrlsGettingUpdated) {
					sourceUrlsGettingUpdated.add(docRepresentingSrcUrl.getSourceUrl());
				}
				if (0 != modDate.getTime()) { // if it ==0 then sourceUrl doesn't exist at all, no need to delete
					this.docsToRemove.add(docRepresentingSrcUrl);
						// (can add documents with just source URL, are treated differently in the core libraries)
				}
				
				SourceFileConfigPojo fileSystem = source.getFileConfig();
				if ((null == fileSystem) && (bIsXml || bIsJson)) {
					fileSystem = new SourceFileConfigPojo();
				}
				XmlToMetadataParser xmlParser = null;
				JsonToMetadataParser jsonParser = null;
				String urlType = extension;
				if (bIsXml) {
					xmlParser = new XmlToMetadataParser(fileSystem.XmlRootLevelValues, 
										fileSystem.XmlIgnoreValues, fileSystem.XmlSourceName, fileSystem.XmlPrimaryKey, 
										fileSystem.XmlAttributePrefix, fileSystem.XmlPreserveCase);
				}//TESTED
				else if (bIsJson) {
					jsonParser = new JsonToMetadataParser(fileSystem.XmlSourceName, fileSystem.XmlRootLevelValues, fileSystem.XmlPrimaryKey, fileSystem.XmlIgnoreValues, maxDocsPerCycle);
				}//TESTED
				
				List<DocumentPojo> partials = null;
				try {
					if (bIsXml) {
						XMLStreamReader xmlStreamReader = null;
						XMLInputFactory factory = XMLInputFactory.newInstance();
						factory.setProperty(XMLInputFactory.IS_COALESCING, true);
						try {							
							xmlStreamReader = factory.createXMLStreamReader(f.getInputStream());
							partials = xmlParser.parseDocument(xmlStreamReader);
						}
						finally {
							if (null != xmlStreamReader) xmlStreamReader.close();
						}
					}//TESTED
					else if (bIsJson) {
						JsonReader jsonReader = null;
						try {							
							jsonReader = new JsonReader(new InputStreamReader(f.getInputStream(), "UTF-8"));
							jsonReader.setLenient(true);
							partials = jsonParser.parseDocument(jsonReader);
						}
						finally {
							if (null != jsonReader) jsonReader.close();
						}
					}//TESTED
					else if (bIsLineOriented) { // Just generate a document for every line
						BufferedReader lineReader = null;
						try {
							lineReader = new BufferedReader(new InputStreamReader(f.getInputStream(), "UTF-8"));
							String line;
							partials = new LinkedList<DocumentPojo>();
							while ((line = lineReader.readLine()) != null) {
								DocumentPojo newDoc = new DocumentPojo();
								newDoc.setFullText(line);
								if (line.length() > 128) {
									newDoc.setDescription(line.substring(0, 128));
								}
								else {
									newDoc.setDescription(line);
								}
								partials.add(newDoc);
							}
						}
						finally {
							if (null != lineReader) lineReader.close();
						}
					}

					MessageDigest md5 = null; // (generates unique urls if the user doesn't below)
					try {
						md5 = MessageDigest.getInstance("MD5");
					} catch (NoSuchAlgorithmException e) {
						// Do nothing, unlikely to happen...
					}					
					int nIndex = 0;
					
					for (DocumentPojo doctoAdd : partials)
					{
						nIndex++;
						doctoAdd.setSource(source.getTitle());
						doctoAdd.setSourceKey(source.getKey());
						doctoAdd.setMediaType(source.getMediaType());
						doctoAdd.setModified(new Date(fileTimestamp));
						doctoAdd.setCreated(new Date());						
						if(null == doctoAdd.getUrl()) { // Normally gets set in xmlParser.parseIncident() - some fallback cases (usually md5)
							if (bIsXml && ((null == fileSystem.XmlRootLevelValues) || fileSystem.XmlRootLevelValues.isEmpty())) {
								doctoAdd.setUrl(f.getUrlString());
							}
							else if (null == doctoAdd.getMetadata()) { // Line oriented case
								doctoAdd.setUrl(new StringBuffer(f.getUrlString()).append("/").append(nIndex).append('.').append(urlType).toString());
							}
							else {
								if (null == md5) { // Will never happen, MD5 always exists
									doctoAdd.setUrl(new StringBuffer(f.getUrlString()).append("/").append(doctoAdd.getMetadata().hashCode()).append('.').append(urlType).toString());
								}
								else { // This is the standard call if the XML parser has not been configured to build the URL
									doctoAdd.setUrl(new StringBuffer(f.getUrlString()).append("/").append(DigestUtils.md5Hex(doctoAdd.getMetadata().toString())).append('.').append(urlType).toString());
								}
							}//TESTED
						}
						doctoAdd.setTitle(f.getName().toString());
						doctoAdd.setPublishedDate(new Date(fileTimestamp));
						doctoAdd.setSourceUrl(f.getUrlString());

						// Always add to files because I'm deleting the source URL
						files.add(doctoAdd);						
					}//TESTED (apart from TOTEST)

				} catch (XMLStreamException e1) {
					errors++;
					_context.getHarvestStatus().logMessage(HarvestExceptionUtils.createExceptionMessage(e1).toString(), true);
				} catch (FactoryConfigurationError e1) {
					errors++;
					_context.getHarvestStatus().logMessage(e1.getMessage(), true);
					
				} catch (IOException e1) {
					errors++;
					_context.getHarvestStatus().logMessage(HarvestExceptionUtils.createExceptionMessage(e1).toString(), true);
				}
				catch (Exception e1) {
					errors++;
					_context.getHarvestStatus().logMessage(HarvestExceptionUtils.createExceptionMessage(e1).toString(), true);					
				}
			}
		}
		else //Tika supports Excel,Word,Powerpoint,Visio, & Outlook Documents
		{
			// (This dedup tells me if it's an add/update vs ignore - qr.isDuplicate higher up tells me if I need to add or update)
			if(needsUpdated_Url(modDate, f.getUrlString(), source))
			{

				Metadata metadata = null;
				Tika tika = null;
				InputStream in = null;
				try {

					doc = new DocumentPojo();
					// Create a tika object
					tika = new Tika();
					// BUGGERY
					// NEED TO LIKELY SET LIMIT TO BE 30MB or 50MB and BYPASS ANYTHING OVER THAT BELOW IS THE CODE TO DO THAT
					// tika.setMaxStringLength(30*1024*1024);
					// Disable the string length limit
					tika.setMaxStringLength(-1);
					//input = new FileInputStream(new File(resourceLocation));
					// Create a metadata object to contain the metadata
					metadata = new Metadata();
					// Parse the file and get the text of the file
					doc.setSource(source.getTitle());
					doc.setSourceKey(source.getKey());
					doc.setMediaType(source.getMediaType());
					String fullText = "";
					
					in = f.getInputStream();
					try {
						fullText = tika.parseToString(in, metadata);
					}
					finally {
						if (null != in) in.close();
					}
					int descCap = 500;
					doc.setFullText(fullText);
					if (descCap > fullText.length())
					{
						descCap = fullText.length();
					}
					doc.setDescription(fullText.substring(0,descCap));
					doc.setModified(new Date(fileTimestamp));
					doc.setCreated(new Date());
					doc.setUrl(f.getUrlString());
					doc.setTitle(f.getName().toString());
					doc.setPublishedDate(new Date(fileTimestamp));
					
					// If the metadata contains a more plausible date then use that
					try {
						String title = metadata.get(Metadata.TITLE);
						if (null != title) {
							doc.setTitle(title);
						}
					}
					catch (Exception e) { // Fine just carry on						
					}
					try { 
						Date date = metadata.getDate(Metadata.CREATION_DATE); // MS Word
						if (null != date) { 
							doc.setPublishedDate(date);
						}
						else {
							date = metadata.getDate(Metadata.DATE); // Dublin
							if (null != date) {
								doc.setPublishedDate(date);
							}
							else {
								date = metadata.getDate(Metadata.ORIGINAL_DATE);
								if (null != date) {
									doc.setPublishedDate(date);
								}
							}
						}
					}
					catch (Exception e) { // Fine just carry on						
					}
					//TESTED
					
					// If the metadata contains a geotag then apply that:
					try {
						String lat = metadata.get(Metadata.LATITUDE);
						String lon = metadata.get(Metadata.LONGITUDE);
						if ((null != lat) && (null != lon)) {
							GeoPojo gt = new GeoPojo();
							gt.lat = Double.parseDouble(lat);
							gt.lon = Double.parseDouble(lon);
							doc.setDocGeo(gt);
						}
					}
					catch (Exception e) { // Fine just carry on						
					}
					
					// Save the entire metadata:
					doc.addToMetadata("_FILE_METADATA_", metadata);

					for(ObjectId communityId: source.getCommunityIds())
					{
						doc.setCommunityId(communityId);
					}
					files.add(doc);

					// Close the input stream
					in.close();
					in = null;

					//TESTED

				} catch (SmbException e) {
					errors++;
					_context.getHarvestStatus().logMessage(HarvestExceptionUtils.createExceptionMessage(e).toString(), true);
				} catch (MalformedURLException e) {
					errors++;
					_context.getHarvestStatus().logMessage(HarvestExceptionUtils.createExceptionMessage(e).toString(), true);
				} catch (UnknownHostException e) {
					errors++;
					_context.getHarvestStatus().logMessage(HarvestExceptionUtils.createExceptionMessage(e).toString(), true);
				}
				catch (IOException e) {
					errors++;
					_context.getHarvestStatus().logMessage(e.getMessage(), true);
				} catch (TikaException e) {
					errors++;
					_context.getHarvestStatus().logMessage(e.getMessage(), true);
				}
				catch (Exception e) {
					errors++;
					_context.getHarvestStatus().logMessage(HarvestExceptionUtils.createExceptionMessage(e).toString(), true);
				}
				finally { // Close the input stream if an error occurs
					if (null != in) {
						try {
							in.close();
						} catch (IOException e) {
							// All good, do nothing
						}
					}
				} // end exception handling
			} // end dedup check
		} // end XML vs "office" app
	}

	//TODO (INF-1831): this takes far too long and also hogs the file harvest sync
	// I think best would be to push files into a multimap and then read down the modified
	// times until one hits a modified that misses
	// (for success_iteration will need to do something different ... not sure what though?
	//  need to spot the last file of the success_iteration and then restart logic from there
	//  but v unclear how to do that... maybe something like step through docs (N:=maxdocs/10? /50?) at a time, check Nth ...
	//  if the doc isn't there then loop back and try all of them from there until another collision occurs?!
	//  ugh it would be nice to have sensible iterators in Java.... instead will have to fill another list as I go...)
	
	private void traverse( InfiniteFile f, SourcePojo source, int depth ) throws Exception {
		if( depth == 0 ) {
			return;
		}

		InfiniteFile[] l;
		try {
			//TODO (INF-1406): made this synchronized to work around what looks like deadlock issue in code
			// This is v undesirable and should be fixed once the underlying bug has been fixed
			// (note in practice this is only an issue for multiple threads going to the same domain)
			synchronized (FileHarvester.class) {
				l = f.listFiles();
				
				for(int i = 0; l != null && i < l.length; i++ ) {
					if (null == l[i]) break; // (reached the end of the list)
					
					// Check to see if the item is a directory or a file that needs to parsed
					// if it is a file then parse the sucker using tika 
					// if it is a directory then use recursion to dive into the directory
					if (files.size() > this.maxDocsPerCycle) {
						source.setReachedMaxDocs();
						break;
					}
					if( l[i].isDirectory() ) {
						// Directories: included unless explicity exclude:
						String path = l[i].getUrlPath();
						boolean bProcess = true;
						if (null != excludeRegex) {
							if (excludeRegex.matcher(path).matches()) {
								bProcess = false;
							}							
						}//TESTED
						if (bProcess) {
							traverse( l[i], source, depth - 1 );
						}
					}
					else {
						// Files: check both include and exclude
						String path = l[i].getUrlPath();
						boolean bProcess = true;
						if (null != includeRegex) {
							if (!includeRegex.matcher(path).matches()) {
								bProcess = false;
							}
						}
						if (bProcess && (null != excludeRegex)) {
							if (excludeRegex.matcher(path).matches()) {
								bProcess = false;
							}							
						}//TESTED
						if (bProcess) {
							parse( l[i], source);
								// (Adds to this.files)
						}
					}
				}
			}
			// (End INF-1406 sync bug, see above explanation)

		} catch (Exception e) {
			if (maxDepth == depth) { // Top level error, abandon ship
				errors++;
				throw e;
			}
			else { // Already had some luck with this URL keep going			
				errors++;
				_context.getHarvestStatus().logMessage(HarvestExceptionUtils.createExceptionMessage(e).toString(), true);
			}
		}
	}


	private boolean needsUpdated_SourceUrl(Date mod, String sourceUrl, SourcePojo source)
	{
		try {					
			DuplicateManager qr = _context.getDuplicateManager();
			return qr.needsUpdated_SourceUrl(mod, sourceUrl, source);
		} 
		catch (Exception e) {
			// Do nothing
		} 
		return false;
	}

	private boolean needsUpdated_Url(Date mod, String url, SourcePojo source)
	{
		try {					
			DuplicateManager qr = _context.getDuplicateManager();

			return qr.needsUpdated_Url(mod, url, source);
		} 
		catch (Exception e) {
			// Do nothing
		} 
		return false;
	}
	@Override
	public boolean canHarvestType(int sourceType) {
		return sourceTypesCanHarvest.contains(sourceType);
	}

	@Override
	public void executeHarvest(HarvestContext context, SourcePojo source, List<DocumentPojo> toAdd, List<DocumentPojo> toUpdate, List<DocumentPojo> toRemove) {
				
		_context = context;
		if (_context.isStandalone()) {
			maxDocsPerCycle = _context.getStandaloneMaxDocs();
		}
		try 
		{
			//logger.debug("Source: " + source.getUrl());

			//create new list for files
			this.files = new LinkedList<DocumentPojo>();
			this.docsToAdd = toAdd;
			this.docsToUpdate = toUpdate;
			this.docsToRemove = toRemove;
			processFiles(source);
			
			//harvested "successfully", post in mongo
			String logMsg = (0 == errors)?(""):(new StringBuffer().append(errors).append(" file error(s).").toString());
			_context.getHarvestStatus().update(source, new Date(), HarvestEnum.in_progress, logMsg, false, false);	
		}
		catch (Exception e)
		{
			errors++;
			_context.getHarvestStatus().update(source,new Date(),HarvestEnum.error,e.getMessage(), true, false);
		}		
	}

}
