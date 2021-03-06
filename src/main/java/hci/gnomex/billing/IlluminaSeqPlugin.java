package hci.gnomex.billing;

import hci.dictionary.model.DictionaryEntry;
import hci.dictionary.model.NullDictionaryEntry;
import hci.dictionary.utility.DictionaryManager;
import hci.gnomex.model.BillingItem;
import hci.gnomex.model.BillingPeriod;
import hci.gnomex.model.BillingTemplate;
import hci.gnomex.model.Hybridization;
import hci.gnomex.model.LabeledSample;
import hci.gnomex.model.NumberSequencingCyclesAllowed;
import hci.gnomex.model.Price;
import hci.gnomex.model.PriceCategory;
import hci.gnomex.model.PriceCriteria;
import hci.gnomex.model.PropertyEntry;
import hci.gnomex.model.Request;
import hci.gnomex.model.RequestCategory;
import hci.gnomex.model.Sample;
import hci.gnomex.model.SequenceLane;
import hci.gnomex.utility.DictionaryHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hibernate.Session;


public class IlluminaSeqPlugin extends BillingPlugin {

  public List<BillingItem> constructBillingItems(Session sess, String amendState, BillingPeriod billingPeriod, PriceCategory priceCategory, Request request, 
      Set<Sample> samples, Set<LabeledSample> labeledSamples, Set<Hybridization> hybs, Set<SequenceLane> lanes, Map<String, ArrayList<String>> sampleToAssaysMap, 
      String billingStatus, Set<PropertyEntry> propertyEntries, BillingTemplate billingTemplate) {

    List<BillingItem> billingItems = new ArrayList<BillingItem>();
    Map seqLaneMap = new HashMap();
    Map seqLaneNoteMap = new HashMap();
    Map<String, NumberSequencingCyclesAllowed> customNumberSequencingCyclesAllowed = new HashMap<String, NumberSequencingCyclesAllowed>();
    DictionaryHelper dh = DictionaryHelper.getInstance(sess);
    
    if (lanes == null || lanes.size() == 0) {
      System.out.println ("[IlluminaSeqPlugin] *** return NO LANES *** " + " idRequest: " + request.getIdRequest());
      return billingItems;
    }
    
    // Get custom NumberSequencingCyclesAllowed
    for (Iterator i = DictionaryManager.getDictionaryEntries("hci.gnomex.model.NumberSequencingCyclesAllowed").iterator(); i.hasNext();) {
      DictionaryEntry de = (DictionaryEntry)i.next();
      if (de instanceof NullDictionaryEntry) {
        continue;
      }
      NumberSequencingCyclesAllowed nsca = (NumberSequencingCyclesAllowed)de;
      
      if (nsca.getCodeRequestCategory().equals(request.getCodeRequestCategory()) && nsca.getIsCustom() != null && nsca.getIsCustom().equals("Y")) {
        String key = nsca.getIdNumberSequencingCyclesAllowed().toString();
        customNumberSequencingCyclesAllowed.put(key, nsca);
      }
    }
    
    // Count up number of sequence lanes for number seq cycles / seq run type
    for(Iterator i = lanes.iterator(); i.hasNext();) {
      SequenceLane seqLane = (SequenceLane)i.next();
      
      
      String key = seqLane.getIdNumberSequencingCyclesAllowed().toString();
      
      // Keep track of the lanes for # cycles/seq run type
      List theLanes = (List)seqLaneMap.get(key);
      if (theLanes == null) {
        theLanes = new ArrayList();
        seqLaneMap.put(key, theLanes);
      }
      theLanes.add(seqLane);
      
      // Show the seq lane numbers in the notes for the billing item
      String notes = (String)seqLaneNoteMap.get(key);
      if (notes == null) {
        notes = "";
      }
      if (notes.length() > 0) {
        notes += ",";
      } 
      notes += seqLane.getNumber();
      seqLaneNoteMap.put(key, notes);
    }
    
    // Now generate a billing item for each seq lane number sequencing cycles  / seq run type
    for(Iterator i = seqLaneMap.keySet().iterator(); i.hasNext();) {
      String idNumberSequencingCyclesAllowed = (String)i.next();
      
      List theLanes = (List)seqLaneMap.get(idNumberSequencingCyclesAllowed);
      
      Integer maxLanesForSample = 0;
      Map<Integer, Integer>numSeqLanesPerSample = new HashMap<Integer, Integer>();
      for(Iterator j = theLanes.iterator(); j.hasNext();) {
        SequenceLane theLane = (SequenceLane)j.next();
        Integer numLanesForSample = numSeqLanesPerSample.get(theLane.getSample().getIdSample());
        if (numLanesForSample == null) {
          numLanesForSample = 1;
        } else {
          numLanesForSample++;
        }
        numSeqLanesPerSample.put(theLane.getSample().getIdSample(), numLanesForSample);
        if (numLanesForSample > maxLanesForSample) {
          maxLanesForSample = numLanesForSample;
        }
      }
      
      int qty = SequenceLane.getMultiplexLaneCount(theLanes, request.getCreateDate());
      System.out.println ("[IlluminaSeqPlugin] qty: " + qty);
      String notes = (String)seqLaneNoteMap.get(idNumberSequencingCyclesAllowed);
      
      // Find the billing price 
      Price price = null;
      for(Iterator i1 = priceCategory.getPrices().iterator(); i1.hasNext();) {
        Price p = (Price)i1.next();
        if (p.getIsActive() != null && p.getIsActive().equals("Y")) {
          for(Iterator i2 = p.getPriceCriterias().iterator(); i2.hasNext();) {
            PriceCriteria criteria = (PriceCriteria)i2.next();
            if (criteria.getFilter1().equals(idNumberSequencingCyclesAllowed)) {
              price = p;
              System.out.println ("[IlluminaSeqPlugin] found price: " + p.getIdPrice() + " idRequest: " + request.getIdRequest());
              break;            
            }
          }
        }
      }
      
      if (customNumberSequencingCyclesAllowed.containsKey(idNumberSequencingCyclesAllowed)) {
        // Custom NumberSequencingCyclesAllowed bill for the whole cell in a single charge.
        RequestCategory category = dh.getRequestCategoryObject(request.getCodeRequestCategory());
        qty = maxLanesForSample;
        System.out.println ("[IlluminaSeqPlugin] requestCategory, qty: " + category.toString() + " qty: " + qty + " idRequest: " + request.getIdRequest());

      }
      
      // Instantiate a BillingItem for the matched price
      if (price != null) {
    	  billingItems.addAll(this.makeBillingItems(request, price, priceCategory, qty, billingPeriod, billingStatus, sess, billingTemplate));
      }
      else
      {
        System.out.println ("[IlluminaSeqPlugin] WARNING WARNING leaving with price null NO BILLING ITEMS ADDED. " + request.getIdRequest());
      }
    }
    
    
    return billingItems;
  }

  



}
