package fromOtherSources;

import java.io.File;  
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import basics.Fact;
import basics.FactComponent;
import basics.N4Reader;
import basics.RDFS;
import basics.YAGO;
import extractors.DataExtractor;
import fromThemes.TransitiveTypeExtractor;
import javatools.administrative.Parameters;
import javatools.datatypes.FinalSet;
import javatools.parsers.Char17;
import utils.FactCollection;
import utils.Theme;

/**
 * Extract images from wikidata
 * 
This class is part of the YAGO project at the Max Planck Institute
for Informatics/Germany and Télécom ParisTech University/France:
http://yago-knowledge.org

This class is copyright 2016 Ghazaleh Haratinezhad Torbati.

YAGO is free software: you can redistribute it and/or modify it
under the terms of the GNU General Public License as published
by the Free Software Foundation, either version 3 of the License,
or (at your option) any later version.

YAGO is distributed in the hope that it will be useful, but WITHOUT
ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public
License for more details.

You should have received a copy of the GNU General Public License
along with YAGO.  If not, see <http://www.gnu.org/licenses/>.
*/

public class WikidataImageExtractor extends DataExtractor {

	public static final Theme WIKIDATAIMAGES = new Theme("wikidataImages", 
			"Images in wikidata dump for entities");

	private static final String WIKIDATA_STATEMENTS = "wikidata_statements";
	private static final String IMAGE_ORIGINALURL_TEMPLATE = "https://upload.wikimedia.org/wikipedia/commons/";
	private static final String IMAGETYPE = "image_";
	private static FactCollection transitiveTypes = new FactCollection();
	private static FactCollection reverseWikidataInstances = new FactCollection();
	
	
	private static final Map<String, List<String>> imageRelations;
	static {
		Map<String, List<String>> tempMap = new HashMap<String, List<String>>();
		tempMap.put("person", Arrays.asList(ImageTypes.image, ImageTypes.imageOfGrave, ImageTypes.coatOfArmsImage, ImageTypes.signature));
		tempMap.put("organization", Arrays.asList(ImageTypes.logoImage, ImageTypes.coatOfArmsImage, ImageTypes.image));
		tempMap.put("event", Arrays.asList(ImageTypes.image));
		tempMap.put("artifact", Arrays.asList(ImageTypes.logoImage, ImageTypes.image));
		tempMap.put("location", Arrays.asList(ImageTypes.flagImage, ImageTypes.sealImage, ImageTypes.coatOfArmsImage,
											  ImageTypes.detailMap, ImageTypes.locatorMapImage, ImageTypes.image));
		tempMap.put("other", Arrays.asList(ImageTypes.image));
		
		imageRelations = Collections.unmodifiableMap(tempMap);
	}
		
	public WikidataImageExtractor(File wikidata) {
		super(wikidata);
	}
	
	public WikidataImageExtractor() {
		this(Parameters.getFile(WIKIDATA_STATEMENTS));
	}

	@Override
	public Set<Theme> input() {
		return (new FinalSet<>(WikidataLabelExtractor.WIKIDATAINSTANCES, TransitiveTypeExtractor.TRANSITIVETYPE));
	}

	@Override
	public Set<Theme> output() {
		return (new FinalSet<>(WIKIDATAIMAGES));
	}

	@Override
	public void extract() throws Exception {
	  // Example of the facts in reverseWikidataInstances:
	  // <http://www.wikidata.org/entity/Q23>  owl:sameAs <George_Washington>      
	  reverseWikidataInstances = WikidataLabelExtractor.WIKIDATAINSTANCES.factCollection().getReverse();
		
	  transitiveTypes = TransitiveTypeExtractor.TRANSITIVETYPE.factCollection();
		
		N4Reader nr = new N4Reader(inputData);
		String yagoEntityMostEnglish = null;
		Fact prevImage = null;
		int imageCounter = 1;
		Map<String, String> images = new HashMap<String, String>();
		while(nr.hasNext()) {
			Fact f = nr.next();
			 
			// We reached a new entity and until the next appearance of "#Item", the statements are about this entity.
			// example: <http://www.wikidata.org/entity/Q1> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://www.wikidata.org/ontology#Item>
			if(f.getObject().endsWith("#Item>")){
				if(!images.isEmpty()){
					String image = pickImage(yagoEntityMostEnglish, images);
					
					// Get original image url from image's wikipage url
					String originalUrl = FactComponent.forUri(getOriginalImageUrl(FactComponent.stripBrackets(image)));
					String imageID = FactComponent.forYagoEntity(IMAGETYPE + imageCounter);
					imageCounter++;
					
					// Saving imageID along with image wiki page url and image original url to the theme
					WIKIDATAIMAGES.write(new Fact(yagoEntityMostEnglish, YAGO.hasImageID, imageID));
					WIKIDATAIMAGES.write(new Fact(imageID, YAGO.hasWikiPage, image));
					WIKIDATAIMAGES.write(new Fact(imageID, YAGO.hasImageUrl, originalUrl));
	        images.clear();
				}
				// reverseWikidataInstances facts like: <http://www.wikidata.org/entity/Q23>  owl:sameAs <George_Washington>
				yagoEntityMostEnglish = getMostEnglishEntityName(reverseWikidataInstances.getFactsWithSubjectAndRelation(f.getSubject(), RDFS.sameas));
				prevImage = null;
			}
			
			// Select the first image per relation for an entity, unless there is a PreferredRank.
			// Format of regular expression is taken from: https://www.wikidata.org/wiki/Property:P18
			// example: <some random id> <https://www.wikidata.org/wiki/Property:P18> <filename.jpg> .
			else if(f.getObject().matches(".*commons\\.wikimedia.*\\.(jpg|jpeg|png|svg|tif|tiff|gif)>$") && yagoEntityMostEnglish != null){
				if(!images.containsKey(f.getRelation()))
					images.put(f.getRelation(), Char17.decodeBackslash(f.getObject()));
				prevImage = f;
			}
			// If the rank of the image was "PreferredRank", replace the existing image with this preferred image.
			// example : <random id> <http://www.wikidata.org/ontology#rank> <http://www.wikidata.org/ontology#NormalRank> . 
			else if(prevImage != null && prevImage.getSubject().equals(f.getSubject()) && f.getRelation().endsWith("#rank>")){
				if(f.getObject().endsWith("#PreferredRank>"))
					images.put(prevImage.getRelation(), prevImage.getObject());
				prevImage = null;
			}
		}
		
    // Saving information of the last entity in the file
		if (!images.isEmpty() && yagoEntityMostEnglish != null)
		{
		  String image = pickImage(yagoEntityMostEnglish, images);
		  String originalUrl = FactComponent.forUri(getOriginalImageUrl(FactComponent.stripBrackets(image)));
		  String imageID = FactComponent.forYagoEntity(IMAGETYPE + imageCounter);
		  
      WIKIDATAIMAGES.write(new Fact(yagoEntityMostEnglish, YAGO.hasImageID, imageID));
      WIKIDATAIMAGES.write(new Fact(imageID, YAGO.hasWikiPage, image));
      WIKIDATAIMAGES.write(new Fact(imageID, YAGO.hasImageUrl, originalUrl));
      images.clear();
		}
		
		nr.close();
	}
	
	/**
	 * Return the most English entity name given all entity names available
	 * @param entityFacts yago entity in different languages
	 * @return most English entity name
	 */
	private static String getMostEnglishEntityName(Set<Fact> entityFacts){
	  // Map of entity names for each language 
    Map<String, String> languageEntityName = new HashMap<>();
    // each entityFact is like: <http://www.wikidata.org/entity/Q23>  owl:sameAs <George_Washington>
    for(Fact f:entityFacts){
      String language = FactComponent.getLanguageOfEntity(f.getObject());
      if (language != null)
        languageEntityName.put(language, f.getObject());
      else
        languageEntityName.put("en", f.getObject());
    }
    
    String mostEnglishLanguage = DictionaryExtractor.mostEnglishLanguage(languageEntityName.keySet());
    return languageEntityName.get(mostEnglishLanguage);
	}
	
	
	/**
	 * Pick the best image with regard to its category using manual order for images for each category.
	 * @param yagoEntity
	 * @param images The images extracted for the yagoEntity
	 * @return picked image
	 * @throws IOException
	 */
	private static String pickImage(String yagoEntity, Map<String, String> images) throws IOException {
	  String image = null;
	  String category = getHighlevelCategory(yagoEntity);
    for(String key : imageRelations.get(category)){
      if(images.containsKey(key)){
        image = images.get(key);
        break;
      }
    }
    // If there were some image(s) but not in the expected types, select first one.
    if(image == null){
      image = images.entrySet().iterator().next().getValue();
    }
	  return image;
	}
	
	
	/**
	 * To make the image's original url, we use the first 2 characters of md5 hashed version of the file name
   * example: input= "https://commons.wikimedia.org/wiki/File:Spelterini_Blüemlisalp.jpg" 
   * file name = "Spelterini_Blüemlisalp.jpg" hashed = "ae1a26d34d6a674d4400c8a1e6fe73f8"
   * original url = https://upload.wikimedia.org/wikipedia/commons/a/ae/Spelterini_Bl%C3%BCemlisalp.jpg
   * @see https://commons.wikimedia.org/wiki/Commons:FAQ#What_are_the_strangely_named_components_in_file_paths.3F 
	 * @param wikiUrl Url to image's wiki page
	 * @return Image's original url 
	 * @throws NoSuchAlgorithmException
	 */
	public static String getOriginalImageUrl(String wikiUrl) throws NoSuchAlgorithmException {
	  MessageDigest md = MessageDigest.getInstance("MD5");
	  
	  String imageName = wikiUrl.substring(wikiUrl.indexOf("/wiki/File:") + "/wiki/File:".length());
	  
	  StringBuffer hashedName = new StringBuffer();
	  md.update(imageName.getBytes(StandardCharsets.UTF_8));
	  byte byteData[] = md.digest();
	  
	  for (int i = 0; i < byteData.length; i++) {
	    hashedName.append(Integer.toString((byteData[i] & 0xff) + 0x100, 16).substring(1));
	  }
	  
    return (IMAGE_ORIGINALURL_TEMPLATE + hashedName.charAt(0) + "/" + hashedName.charAt(0) + hashedName.charAt(1) + "/" + imageName);
	}
	
	/**
	 * Return the high level category of the entity based on yago transitive types
	 * @param entity Yago entity
	 * @return High level category of the entity
	 * @throws IOException
	 */
	private static String getHighlevelCategory(String entity) throws IOException {
		Set<Fact> facts = transitiveTypes.getFactsWithSubjectAndRelation(entity, RDFS.type);
		String category = "other";
		
		for (Fact fact:facts){
			String factObject = fact.getObject();
			if (factObject.contains("person")){
				category = "person";
				break;
			}
			if (factObject.contains("location")){
				category = "location";
				break;
			}
			if (factObject.contains("organization")){
				category = "organization";
				break;
			}
			if (factObject.contains("artifact")){
				category = "artifact";
				break;
			}
			if (factObject.contains("event")){
				category = "event";
				break;
			}		
		}
		return category;
	}

}