package hci.gnomex.controller;

import hci.gnomex.constants.Constants;
import hci.gnomex.model.Hybridization;
import hci.gnomex.model.Label;
import hci.gnomex.model.LabeledSample;
import hci.gnomex.model.LabelingReactionSize;
import hci.gnomex.model.Request;
import hci.gnomex.model.RequestCategory;
import hci.gnomex.model.Sample;
import hci.gnomex.model.SampleCharacteristic;
import hci.gnomex.model.SampleCharacteristicEntry;
import hci.gnomex.model.SequenceLane;
import hci.gnomex.model.Slide;
import hci.gnomex.model.SlideDesign;
import hci.gnomex.model.Step;
import hci.gnomex.model.TreatmentEntry;
import hci.gnomex.model.Visibility;
import hci.gnomex.model.WorkItem;
import hci.gnomex.security.SecurityAdvisor;
import hci.gnomex.utility.DictionaryHelper;
import hci.gnomex.utility.HibernateSession;
import hci.gnomex.utility.HybNumberComparator;
import hci.gnomex.utility.MailUtil;
import hci.gnomex.utility.RequestEmailBodyFormatter;
import hci.gnomex.utility.RequestParser;
import hci.gnomex.utility.SampleNumberComparator;
import hci.gnomex.utility.SequenceLaneNumberComparator;
import hci.gnomex.utility.WorkItemHybParser;
import hci.framework.control.Command;
import hci.framework.control.RollBackCommandException;

import java.io.File;
import java.io.Serializable;
import java.io.StringReader;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import javax.mail.MessagingException;
import javax.naming.NamingException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.hibernate.Session;
import org.jdom.Document;
import org.jdom.JDOMException;
import org.jdom.input.SAXBuilder;
import org.jdom.output.XMLOutputter;




public class SaveRequest extends GNomExCommand implements Serializable {
  
 
  
  // the static field for logging in Log4J
  private static org.apache.log4j.Logger log = org.apache.log4j.Logger.getLogger(SaveRequest.class);
  
  private String           requestXMLString;
  private Document         requestDoc;
  private RequestParser    requestParser;
  
  private String           appURL;
  private String           serverName;
  
  private DictionaryHelper dictionaryHelper = null;

  private Integer          idProject;
  private Map              labelMap = new HashMap();
  private Map              idSampleMap = new HashMap();
  private TreeSet          hybs = new TreeSet(new HybNumberComparator());
  private TreeSet          samples = new TreeSet(new SampleNumberComparator());
  private TreeSet          sequenceLanes = new TreeSet(new SequenceLaneNumberComparator());
  

  private Map              channel1SampleMap = new HashMap();
  private Map              channel2SampleMap = new HashMap();
  
  private Integer          idLabelingProtocolDefault;
  private Integer          idHybProtocolDefault;
  private Integer          idScanProtocolDefault;
  private Integer          idFeatureExtractionProtocolDefault;
  
  
  public void validate() {
  }
  
  public void loadCommand(HttpServletRequest request, HttpSession session) {
    
    
    
    if (request.getParameter("requestXMLString") != null && !request.getParameter("requestXMLString").equals("")) {
      requestXMLString = request.getParameter("requestXMLString");
      this.requestXMLString = this.requestXMLString.replaceAll("&", "&amp;");
    }
    
    StringReader reader = new StringReader(requestXMLString);
    try {
      SAXBuilder sax = new SAXBuilder();
      requestDoc = sax.build(reader);
      requestParser = new RequestParser(requestDoc, this.getSecAdvisor());
    } catch (JDOMException je ) {
      log.error( "Cannot parse requestXMLString", je );
      this.addInvalidField( "RequestXMLString", "Invalid request xml");
    }
    
    
    if (request.getParameter("idProject") != null && !request.getParameter("idProject").equals("")) {
      idProject = new Integer(request.getParameter("idProject"));
    }
    
    try {
      appURL = this.getLaunchAppURL(request);      
    } catch (Exception e) {
      log.warn("Cannot get launch app URL in SaveRequest", e);
    }

    serverName = request.getServerName();
  }

  public Command execute() throws RollBackCommandException {
    
    try {
      Session sess = HibernateSession.currentSession(this.getUsername());
      
      requestParser.parse(sess);
      
      
      if (requestParser.isNewRequest()) {
        if (!this.getSecAdvisor().isGroupIAmMemberOrManagerOf(requestParser.getRequest().getIdLab())) {
          this.addInvalidField("PermissionLab", "Insufficient permissions to submit the request for this lab.");                  
        }
      } else {
        if (!this.getSecAdvisor().canUpdate(requestParser.getRequest())) {
          this.addInvalidField("PermissionAddRequest", "Insufficient permissions to edit the request.");
        }          
      }
      
      if (this.isValid()) {
        List labels = sess.createQuery("SELECT label from Label label").list();
        for(Iterator i = labels.iterator(); i.hasNext();) {
          Label l = (Label)i.next();
          labelMap.put(l.getLabel(), l.getIdLabel());
        }
              
        // save request
        saveRequest(requestParser.getRequest(), sess);
        
        // save samples
        int sampleCount = 1;
        for(Iterator i = requestParser.getSampleIds().iterator(); i.hasNext();) {
          String idSampleString = (String)i.next();
          boolean isNewSample = requestParser.isNewRequest() || idSampleString == null || idSampleString.equals("") || idSampleString.startsWith("Sample");
          Sample sample = (Sample)requestParser.getSampleMap().get(idSampleString);
          saveSample(idSampleString, sample, sess, sampleCount);
          

          

          // if this is a new request, create QC work items for each sample
          if ((requestParser.isNewRequest()  || isNewSample) && 
              !requestParser.getRequest().getCodeRequestCategory().equals(RequestCategory.SOLEXA_REQUEST_CATEGORY)) {
            WorkItem workItem = new WorkItem();
            workItem.setIdRequest(requestParser.getRequest().getIdRequest());
            workItem.setCodeStepNext(Step.QUALITY_CONTROL_STEP);
            workItem.setSample(sample);
            workItem.setCreateDate(new java.sql.Date(System.currentTimeMillis()));
            sess.save(workItem);
          }
          
          sampleCount++;
        }
    
        // save hybs
        if (!requestParser.getHybInfos().isEmpty()) {
          int hybCount = 1;
          int newHybCount = 0;
          for(Iterator i = requestParser.getHybInfos().iterator(); i.hasNext();) {
            RequestParser.HybInfo hybInfo = (RequestParser.HybInfo)i.next();
            boolean isNewHyb = requestParser.isNewRequest() || hybInfo.getIdHybridization() == null || hybInfo.getIdHybridization().startsWith("Hyb");
            if (isNewHyb) {
              newHybCount++;
            }
            saveHyb(hybInfo, sess, hybCount);
            hybCount++;
          }
          if (requestParser.isNewRequest()) {
            requestParser.getRequest().setHybridizations(hybs);        
          } else if (newHybCount > 0) {
            requestParser.getRequest().getHybridizations().addAll(hybs);
            
          }
        }
        
        // save sequence lanes
        if (!requestParser.getSequenceLaneInfos().isEmpty()) {
          int laneCount = 1;
          for(Iterator i = requestParser.getSequenceLaneInfos().iterator(); i.hasNext();) {
            RequestParser.SequenceLaneInfo laneInfo = (RequestParser.SequenceLaneInfo)i.next();
            saveSequenceLane(laneInfo, sess, laneCount);
            laneCount++;
          }
          if (requestParser.isNewRequest()) {
            requestParser.getRequest().setSequenceLanes(sequenceLanes);        
          }                
          
        }
        
        
        sess.save(requestParser.getRequest());
        sess.flush();

        // Create microarray data directories for request.
        if (requestParser.isNewRequest()) {
          this.createResultDirectories(serverName, requestParser.getRequest());
        }

        XMLOutputter out = new org.jdom.output.XMLOutputter();
        
      

        this.xmlResult = "<SUCCESS idRequest=\"" + requestParser.getRequest().getIdRequest() + "\" requestNumber=\"" + requestParser.getRequest().getNumber()  + "\"/>";
        
        if (requestParser.isNewRequest()) {
          sess.refresh(requestParser.getRequest());
          if (requestParser.getRequest().getAppUser() != null
              && requestParser.getRequest().getAppUser().getEmail() != null
              && !requestParser.getRequest().getAppUser().getEmail().equals("")) {
            try {
              sendConfirmationEmail(sess, requestParser.getRequest());
            } catch (Exception e) {
              log.error("An error occurred while trying to email request submit confirmation in SaveRequest.", e);
              e.printStackTrace();
            }
          } else {
            log.error("Unable to send confirmation email notifying submitter that request "
                    + requestParser.getRequest().getNumber()
                    + " has been submitted.  Request submitter or request submitter email is blank.");
          }
        }
        
      }
    
      if (isValid()) {
        setResponsePage(this.SUCCESS_JSP);
      } else {
        setResponsePage(this.ERROR_JSP);
      }
      
    }catch (Exception e){
      log.error("An exception has occurred in SaveRequest ", e);
      e.printStackTrace();
      throw new RollBackCommandException(e.getMessage());
        
    }finally {
      try {
        HibernateSession.closeSession();        
      } catch(Exception e) {
        
      }
    }
    
    return this;
  }
  
  
  private void saveRequest(Request request, Session sess) throws Exception {
    

    sess.save(request);
    
    if (requestParser.isNewRequest()) {
      request.setNumber(request.getIdRequest().toString() + "R");
      request.setCodeVisibility(Visibility.VISIBLE_TO_GROUP_MEMBERS);
      sess.save(request);
    }
    
    sess.flush();  
  }
  
  
  private void saveSample(String idSampleString, Sample sample, Session sess, int sampleCount) throws Exception {

    sample.setIdRequest(requestParser.getRequest().getIdRequest());
    sess.save(sample);
    
    boolean isNewSample = requestParser.isNewRequest() || idSampleString == null || idSampleString.equals("") || idSampleString.startsWith("Sample");
    
    if (isNewSample) {
      sample.setNumber(requestParser.getRequest().getIdRequest().toString() + "X" + sampleCount);
      sess.save(sample);
    }  
    
    
    // Delete the existing sample characteristic entries
    if (!isNewSample) {
      for(Iterator i = sample.getSampleCharacteristicEntries().iterator(); i.hasNext();) {
        SampleCharacteristicEntry entry = (SampleCharacteristicEntry)i.next();
        sess.delete(entry);
      }
    }

    // Create sample characteristic entries
    Map sampleAnnotations = (Map)requestParser.getSampleAnnotationMap().get(idSampleString);
    for(Iterator i = sampleAnnotations.keySet().iterator(); i.hasNext(); ) {
     
      String code = (String)i.next();
      String value = (String)sampleAnnotations.get(code);
      
      
      SampleCharacteristicEntry entry = new SampleCharacteristicEntry();
      entry.setIdSample(sample.getIdSample());
      if (code.equals(SampleCharacteristic.OTHER)) {
          entry.setOtherLabel(requestParser.getOtherCharacteristicLabel());
      }
      entry.setCodeSampleCharacteristic(code);
      entry.setValue(value);
        
      sess.save(entry);
    }

    // Delete the existing sample treatments
    if (!isNewSample) {
      for(Iterator i = sample.getTreatmentEntries().iterator(); i.hasNext();) {
        TreatmentEntry entry = (TreatmentEntry)i.next();
        sess.delete(entry);
      }
    }
    
    // Add treatment
    String treatment = (String)requestParser.getSampleTreatmentMap().get(idSampleString);
    if(requestParser.getShowTreatments() && treatment != null && !treatment.equals("")) {
      TreatmentEntry entry = new TreatmentEntry();
      entry.setIdSample(sample.getIdSample());
      entry.setTreatment(treatment);
      sess.save(entry);
    }
        
    
    sess.flush();
    
    idSampleMap.put(idSampleString, sample.getIdSample());
    samples.add(sample);
  }

  
  private void saveHyb(RequestParser.HybInfo hybInfo, Session sess, int hybCount) throws Exception {

    // Figure out the default protocol for the given sample type and microarray category.
    StringBuffer buf = new StringBuffer();
    buf.append("SELECT x.idLabelingProtocolDefault, x.idHybProtocolDefault, x.idScanProtocolDefault, x.idFeatureExtractionProtocolDefault ");
    buf.append(" FROM  SampleTypeMicroarrayCategory x ");
    buf.append(" WHERE x.idSampleType = " + requestParser.getRequest().getIdSampleTypeDefault());
    buf.append(" AND   x.codeMicroarrayCategory = '" + requestParser.getRequest().getCodeMicroarrayCategory() + "'");
    List defaultProtocolIds = sess.createQuery(buf.toString()).list();
    if (defaultProtocolIds.size() > 0) {
      Object[] row = (Object[])defaultProtocolIds.get(0);
      idLabelingProtocolDefault          = (Integer)row[0]; 
      idHybProtocolDefault               = (Integer)row[1]; 
      idScanProtocolDefault              = (Integer)row[2]; 
      idFeatureExtractionProtocolDefault = (Integer)row[3]; 
    }

    
    Hybridization hyb = null;
    boolean isNewHyb = requestParser.isNewRequest() || hybInfo.getIdHybridization() == null || hybInfo.getIdHybridization().startsWith("Hyb");
    
    
    if (isNewHyb) {
      hyb = new Hybridization();
      hyb.setCreateDate(new Date(System.currentTimeMillis()));
      hyb.setIdHybProtocol(idHybProtocolDefault);
      hyb.setIdScanProtocol(idScanProtocolDefault);
      hyb.setIdFeatureExtractionProtocol(idFeatureExtractionProtocolDefault);
      isNewHyb = true;
    } else {
      hyb = (Hybridization)sess.load(Hybridization.class, new Integer(hybInfo.getIdHybridization()));
    }
    
    
    Integer idSampleChannel1Real = null;
    if (hybInfo.getIdSampleChannel1String() != null && !hybInfo.getIdSampleChannel1String().equals("")) {
      idSampleChannel1Real = (Integer)idSampleMap.get(hybInfo.getIdSampleChannel1String());
    }
    Integer idSampleChannel2Real = null;
    if (hybInfo.getIdSampleChannel2String() != null && !hybInfo.getIdSampleChannel2String().equals("")) {
     idSampleChannel2Real =  (Integer)idSampleMap.get(hybInfo.getIdSampleChannel2String());
    }
    
    LabeledSample labeledSampleChannel1 = null;
    LabeledSample labeledSampleChannel2 = null;
    if (isNewHyb) {
     Integer idLabeledSampleChannel1 = (Integer)channel1SampleMap.get(idSampleChannel1Real);
     
     if (!channel1SampleMap.containsKey(idSampleChannel1Real)) {
        labeledSampleChannel1 = new LabeledSample();
        labeledSampleChannel1.setIdSample(idSampleChannel1Real);
        labeledSampleChannel1.setIdLabel((Integer)labelMap.get("Cy3"));
        labeledSampleChannel1.setIdRequest(requestParser.getRequest().getIdRequest());
        labeledSampleChannel1.setCodeLabelingReactionSize(LabelingReactionSize.STANDARD);
        labeledSampleChannel1.setNumberOfReactions(new Integer(1));
        labeledSampleChannel1.setIdLabelingProtocol(idLabelingProtocolDefault);
        sess.save(labeledSampleChannel1);
        
        idLabeledSampleChannel1 = labeledSampleChannel1.getIdLabeledSample();
        
        channel1SampleMap.put(idSampleChannel1Real, idLabeledSampleChannel1);
      }
      hyb.setIdLabeledSampleChannel1(idLabeledSampleChannel1);
      
      
      if (idSampleChannel2Real != null) {
        
        Integer idLabeledSampleChannel2 = (Integer)channel2SampleMap.get(idSampleChannel2Real);
        
        if (!channel2SampleMap.containsKey(idSampleChannel2Real)) {
          labeledSampleChannel2 = new LabeledSample();
          labeledSampleChannel2.setIdSample(idSampleChannel2Real);
          labeledSampleChannel2.setIdLabel((Integer)labelMap.get("Cy5"));
          labeledSampleChannel2.setIdRequest(requestParser.getRequest().getIdRequest());
          labeledSampleChannel2.setCodeLabelingReactionSize(LabelingReactionSize.STANDARD);
          labeledSampleChannel2.setNumberOfReactions(new Integer(1));
          labeledSampleChannel2.setIdLabelingProtocol(idLabelingProtocolDefault);
          
          sess.save(labeledSampleChannel2);
          
          idLabeledSampleChannel2 = labeledSampleChannel2.getIdLabeledSample();
          
          channel2SampleMap.put(idSampleChannel2Real, idLabeledSampleChannel2);          
        }   
        hyb.setIdLabeledSampleChannel2(idLabeledSampleChannel2);
      }
    } else {
      boolean changedChannelSample = false;
      
      // If the sample has changed, for an existing hyb, create a new labeled sample and 
      // delete the old one
      if ((hyb.getLabeledSampleChannel1() == null && idSampleChannel1Real != null) ||
          (hyb.getLabeledSampleChannel1() != null && idSampleChannel1Real == null) ||
          (hyb.getLabeledSampleChannel1() != null && 
           idSampleChannel1Real != null && 
           !hyb.getLabeledSampleChannel1().getIdSample().equals(idSampleChannel1Real))) {
        
        LabeledSample labeledSampleObsoleted = null;
        if (hyb.getIdLabeledSampleChannel1() != null) {
          labeledSampleObsoleted = hyb.getLabeledSampleChannel1();
        }
        
        // If the Cy3 Sample has been is filled in
        if (idSampleChannel1Real != null) {
          Integer idLabeledSampleChannel1 = null;
          if (channel1SampleMap.containsKey(idSampleChannel1Real)) {
            idLabeledSampleChannel1 = (Integer)channel1SampleMap.get(idSampleChannel1Real);
          } else {
            labeledSampleChannel1 = new LabeledSample();
            labeledSampleChannel1.setIdSample(idSampleChannel1Real);
            labeledSampleChannel1.setIdLabel((Integer)labelMap.get("Cy3"));
            labeledSampleChannel1.setIdRequest(requestParser.getRequest().getIdRequest());
            labeledSampleChannel1.setCodeLabelingReactionSize(LabelingReactionSize.STANDARD);
            labeledSampleChannel1.setNumberOfReactions(new Integer(1));
            labeledSampleChannel1.setIdLabelingProtocol(idLabelingProtocolDefault);

            sess.save(labeledSampleChannel1);
            idLabeledSampleChannel1 = labeledSampleChannel1.getIdLabeledSample();
            channel1SampleMap.put(idSampleChannel1Real, idLabeledSampleChannel1);            
          }
          
          
          
          hyb.setIdLabeledSampleChannel1(idLabeledSampleChannel1);
        }
        // If the Cy3 Sample has been blanked out
        else {
          hyb.setIdLabeledSampleChannel1(null);
        }
        sess.flush();
        
        if (labeledSampleObsoleted != null) {
          //  Replace the labeled sample on the labeling worklist (if present).
          List referencingWorkItems = sess.createQuery("SELECT wi from WorkItem wi join wi.labeledSample as ls where ls.idLabeledSample = " + labeledSampleObsoleted.getIdLabeledSample()).list();
          if (referencingWorkItems.size() > 0) {
            for(Iterator i1 = referencingWorkItems.iterator(); i1.hasNext();) {
              WorkItem wi = (WorkItem)i1.next();
              if (labeledSampleChannel1 != null) {
                wi.setLabeledSample(labeledSampleChannel1);
              } else {
                sess.delete(wi);
              }
            }              
          } 
          
          // Get rid of the labeled sample that was replaced
          List referencingHybs = sess.createQuery("SELECT h from Hybridization h where h.idLabeledSampleChannel1 = " + labeledSampleObsoleted.getIdLabeledSample()).list();
          if (referencingHybs.size() == 0) {
            sess.delete(labeledSampleObsoleted);
          }

        }

        changedChannelSample = true;          

      } 
        

      if ((hyb.getLabeledSampleChannel2() == null && idSampleChannel2Real != null) ||
          (hyb.getLabeledSampleChannel2() != null && idSampleChannel2Real == null) ||
          (hyb.getLabeledSampleChannel2() != null && 
           idSampleChannel2Real != null && 
           !hyb.getLabeledSampleChannel2().getIdSample().equals(idSampleChannel2Real))) {

        
        LabeledSample labeledSampleObsoleted = null;
        if (hyb.getIdLabeledSampleChannel1() != null) {
          labeledSampleObsoleted = hyb.getLabeledSampleChannel2();
        }
        // If the Cy5 Sample has been filled in
        if (idSampleChannel2Real != null) {
          Integer idLabeledSampleChannel2 = null;
          if (channel2SampleMap.containsKey(idSampleChannel2Real)) {
            idLabeledSampleChannel2 = (Integer)channel2SampleMap.get(idSampleChannel2Real);
          } else { 
            labeledSampleChannel2 = new LabeledSample();
            labeledSampleChannel2.setIdSample(idSampleChannel2Real);
            labeledSampleChannel2.setIdLabel((Integer)labelMap.get("Cy5"));
            labeledSampleChannel2.setIdRequest(requestParser.getRequest().getIdRequest());
            labeledSampleChannel2.setCodeLabelingReactionSize(LabelingReactionSize.STANDARD);
            labeledSampleChannel2.setNumberOfReactions(new Integer(1));
            labeledSampleChannel2.setIdLabelingProtocol(idLabelingProtocolDefault);

            sess.save(labeledSampleChannel2);
            idLabeledSampleChannel2 = labeledSampleChannel2.getIdLabeledSample();     
            channel2SampleMap.put(idSampleChannel2Real, idLabeledSampleChannel2);   
          }
          
          hyb.setIdLabeledSampleChannel2(idLabeledSampleChannel2);
        } 
        // If the Cy5 Sample has been blanked out
        else {
          
          hyb.setIdLabeledSampleChannel2(null);
        }
        sess.flush();
        
        if (labeledSampleObsoleted != null) {
          // Replace the labeled sample on the labeling worklist (if present).
          List referencingWorkItems = sess.createQuery("SELECT wi from WorkItem wi join wi.labeledSample as ls where ls.idLabeledSample = " + labeledSampleObsoleted.getIdLabeledSample()).list();
          if (referencingWorkItems.size() > 0) {
            for(Iterator i1 = referencingWorkItems.iterator(); i1.hasNext();) {
              WorkItem wi = (WorkItem)i1.next();
              if (labeledSampleChannel2 != null) {
                wi.setLabeledSample(labeledSampleChannel2);
              } else {
                sess.delete(wi);
              }
            } 
          } 

          // Get rid of the labeled sample that was replaced
          List referencingHybs = sess.createQuery("SELECT h from Hybridization h where h.idLabeledSampleChannel2 = " + labeledSampleObsoleted.getIdLabeledSample()).list();
          if (referencingHybs.size() == 0) {
            sess.delete(labeledSampleObsoleted);
          }
        }

        changedChannelSample = true;          

      } 
      
      // If the user has not changed the sample designations and the user can manage workflow, 
      // save any changes made to workflow fields.
      if (this.getSecAdvisor().hasPermission(SecurityAdvisor.CAN_MANAGE_WORKFLOW)) {
        
        // Labeling reaction for channel1 labeled sample
        if (!changedChannelSample) {
          if (hyb.getLabeledSampleChannel1() != null) {
            if (hybInfo.getLabelingCompletedChannel1().equals("Y") && 
                hyb.getLabeledSampleChannel1().getLabelingDate() == null) {
              hyb.getLabeledSampleChannel1().setLabelingDate(new java.sql.Date(System.currentTimeMillis()));              
            }
            hyb.getLabeledSampleChannel1().setLabelingBypassed(hybInfo.getLabelingBypassedChannel1());            
            hyb.getLabeledSampleChannel1().setLabelingFailed(hybInfo.getLabelingFailedChannel1());            
            hyb.getLabeledSampleChannel1().setIdLabelingProtocol(hybInfo.getIdLabelingProtocolChannel1());
            hyb.getLabeledSampleChannel1().setLabelingYield(hybInfo.getLabelingYieldChannel1());
            hyb.getLabeledSampleChannel1().setNumberOfReactions(hybInfo.getNumberOfReactionsChannel1());
            hyb.getLabeledSampleChannel1().setCodeLabelingReactionSize(hybInfo.getCodeLabelingReactionSizeChannel1());
          }
          
          // Labeling reaction for channel2  labeled sample
          if (hyb.getLabeledSampleChannel2() != null) {
            if (hybInfo.getLabelingCompletedChannel2().equals("Y") && 
                hyb.getLabeledSampleChannel2().getLabelingDate() == null) {
              hyb.getLabeledSampleChannel2().setLabelingDate(new java.sql.Date(System.currentTimeMillis()));              
            } 
            hyb.getLabeledSampleChannel2().setLabelingBypassed(hybInfo.getLabelingBypassedChannel2());            
            hyb.getLabeledSampleChannel2().setLabelingFailed(hybInfo.getLabelingFailedChannel2());            
            hyb.getLabeledSampleChannel2().setIdLabelingProtocol(hybInfo.getIdLabelingProtocolChannel2());
            hyb.getLabeledSampleChannel2().setLabelingYield(hybInfo.getLabelingYieldChannel2());
            hyb.getLabeledSampleChannel2().setNumberOfReactions(hybInfo.getNumberOfReactionsChannel2());
            hyb.getLabeledSampleChannel2().setCodeLabelingReactionSize(hybInfo.getCodeLabelingReactionSizeChannel2());
          }            
        }

        //
        // Hyb workflow
        //
        hyb.setIdHybProtocol(hybInfo.getIdHybProtocol());
        hyb.setIdScanProtocol(hybInfo.getIdScanProtocol());
        hyb.setIdFeatureExtractionProtocol(hybInfo.getIdFeatureExtractionProtocol());

        if (hybInfo.getHybCompleted().equals("Y") && hyb.getHybDate() == null) {
          hyb.setHybDate(new java.sql.Date(System.currentTimeMillis())); 
        }
        hyb.setHybFailed(hybInfo.getHybFailed());
        hyb.setHybBypassed(hybInfo.getHybBypassed());
        
        
        if (hybInfo.getExtractionCompleted().equals("Y") && hyb.getExtractionDate() == null) {
          hyb.setExtractionDate(new java.sql.Date(System.currentTimeMillis())); 
        }
        hyb.setExtractionFailed(hybInfo.getExtractionFailed());
        hyb.setExtractionBypassed(hybInfo.getExtractionBypassed());
        
        // Save the slide
        Slide slide = hyb.getSlide();
        if (hybInfo.getSlideBarcode() != null) {
          slide = WorkItemHybParser.getSlideForHyb(sess, hyb, hyb.getIdSlideDesign(), hybInfo.getSlideBarcode(), requestParser.getRequest().getIdRequest());
          
          // Create a new slide if one doesn't already exist
          if (slide == null) {
            slide = new Slide();
            sess.save(slide);
            
            // If we are switching out the old slide, we need to delete the old one if there are not any references to it.
            if (hyb.getSlide() != null) {
              WorkItemHybParser.deleteOrphanSlide(sess, hyb, requestParser.getRequest().getIdRequest()); 
            }
            
          }
          
          // Assign the slide to the hyb
          hyb.setIdSlide(slide.getIdSlide());

          // Set the slideDesign
          slide.setIdSlideDesign(hyb.getIdSlideDesign());

          // Set the barcode
          slide.setBarcode(hybInfo.getSlideBarcode());
        
          // Set the array coordinate
          WorkItemHybParser.setArrayCoordinate(sess, hyb, slide, hybInfo.getArrayCoordinateName(), requestParser.getRequest().getIdRequest());
        }
      }


    }
    
    String codeSlideSource = hybInfo.getCodeSlideSource();
    hyb.setCodeSlideSource(codeSlideSource);      
    
    if (hybInfo.getIdSlideDesign() != null) {
      hyb.setIdSlideDesign(hybInfo.getIdSlideDesign());      
    } else {
      List slideDesigns = sess.createQuery("select sd from SlideDesign sd where sd.idSlideProduct = " + requestParser.getRequest().getIdSlideProduct()).list();
      if (slideDesigns.size() > 1) {
        throw new Exception("Cannot set slide design because multiple slide designs exist for slide product " + requestParser.getRequest().getIdSlideProduct());
      } else if (slideDesigns.size() == 0) {
        throw new Exception("Cannot set slide design because no slide designs exist for slide product " + requestParser.getRequest().getIdSlideProduct());
      }
      SlideDesign sd = (SlideDesign)slideDesigns.get(0);
      hyb.setIdSlideDesign(sd.getIdSlideDesign());      
    }
    
    hyb.setNotes(hybInfo.getNotes());
    
 
    sess.save(hyb);
    
    if (isNewHyb) {
      hyb.setNumber(requestParser.getRequest().getIdRequest().toString() + "E" + hybCount);
      sess.save(hyb);
      sess.flush();
      
      sess.refresh(hyb);
      if (hyb.getLabeledSampleChannel1() != null) {
        sess.refresh(hyb.getLabeledSampleChannel1());
      }
      if (hyb.getLabeledSampleChannel2() != null) {
        sess.refresh(hyb.getLabeledSampleChannel2());        
      }
      hybs.add(hyb);
      
    }
    
    
    
    
    sess.flush();
  }
  
  private void saveSequenceLane(RequestParser.SequenceLaneInfo sequenceLaneInfo, Session sess, int laneCount) throws Exception {

    
    SequenceLane sequenceLane = null;
    boolean isNewSequenceLane = requestParser.isNewRequest() || sequenceLaneInfo.getIdSequenceLane() == null || sequenceLaneInfo.getIdSequenceLane().startsWith("SequenceLane");
    
    
    if (isNewSequenceLane) {
      sequenceLane = new SequenceLane();
      sequenceLane.setIdRequest(requestParser.getRequest().getIdRequest());
      sequenceLane.setCreateDate(new Date(System.currentTimeMillis()));
      isNewSequenceLane = true;
    } else {
      sequenceLane = (SequenceLane)sess.load(SequenceLane.class, new Integer(sequenceLaneInfo.getIdSequenceLane()));
    }
    
    
    Integer idSampleReal = null;
    if (sequenceLaneInfo.getIdSampleString() != null && !sequenceLaneInfo.getIdSampleString().equals("") && !sequenceLaneInfo.getIdSampleString().equals("0")) {
      idSampleReal = (Integer)idSampleMap.get(sequenceLaneInfo.getIdSampleString());
    }
    sequenceLane.setIdSample(idSampleReal); 
    
    sequenceLane.setIdFlowCellType(sequenceLaneInfo.getIdFlowCellType());      
    sequenceLane.setIdNumberSequencingCycles(sequenceLaneInfo.getIdNumberSequencingCycles());      
    sequenceLane.setIdGenomeBuildAlignTo(sequenceLaneInfo.getIdGenomeBuildAlignTo());      
    sequenceLane.setAnalysisInstructions(sequenceLaneInfo.getAnalysisInstructions());      
     
    sess.save(sequenceLane);
    
    if (isNewSequenceLane) {
      sequenceLane.setNumber(requestParser.getRequest().getIdRequest().toString() + "L" + laneCount);
      sess.save(sequenceLane);
      sess.flush();
      
      sequenceLanes.add(sequenceLane);
    }
    
    sess.flush();
  }
  
  
  private void sendConfirmationEmail(Session sess, Request request) throws NamingException, MessagingException {
    
    if (dictionaryHelper == null) {
      dictionaryHelper = new DictionaryHelper();
      dictionaryHelper.getDictionaries(sess);
    }
    
    StringBuffer introNote = new StringBuffer();
    String trackRequestURL = appURL + "?requestNumber=" + requestParser.getRequest().getNumber() + "&launchWindow=" + Constants.WINDOW_TRACK_REQUESTS;
    introNote.append("Request " + requestParser.getRequest().getNumber() + " has been submitted to the Microarray Core Facility.  You will receive email notification when the experiment is complete.");
    introNote.append("<br>To track progress on the request, click <a href=\"" + trackRequestURL + "\">" + Constants.APP_NAME + " - " + Constants.WINDOW_NAME_TRACK_REQUESTS + "</a>.");
    
    RequestEmailBodyFormatter emailFormatter = new RequestEmailBodyFormatter(sess, dictionaryHelper, requestParser.getRequest(), samples, hybs, sequenceLanes, introNote.toString());
    String subject = dictionaryHelper.getRequestCategory(request.getCodeRequestCategory()) + " Request " + requestParser.getRequest().getNumber() + " submitted";
    
    boolean send = false;
    if (serverName.equals(Constants.PRODUCTION_SERVER)) {
      send = true;
    } else {
      if (request.getAppUser().getEmail().equals(Constants.DEVELOPER_EMAIL)) {
        send = true;
        subject = "TEST - " + subject;
      }
    }
    
    if (send) {
      MailUtil.send(request.getAppUser().getEmail(), 
          null,
          Constants.EMAIL_BIOINFORMATICS_MICROARRAY, 
          subject, 
          emailFormatter.format(),
          true);      
    }
    
  }
  
  private void createResultDirectories(String serverName, Request req) {
    String microarrayDir = null;
    if (serverName.equals(Constants.PRODUCTION_SERVER)) {
      microarrayDir = Constants.MICROARRAY_DIRECTORY;
    } else {
      microarrayDir = Constants.TEST_MICROARRAY_DIRECTORY;
    }
    
    String createYear = this.formatDate(req.getCreateDate(), this.DATE_OUTPUT_ALTIO).substring(0,4);
    String rootDir = microarrayDir + createYear;
    
    boolean success = false;
    if (!new File(rootDir).exists()) {
      success = (new File(rootDir)).mkdir();
      if (!success) {
        log.error("Unable to create directory " + rootDir);      
      }      
    }
    
    String directoryName = microarrayDir + createYear + "\\" + req.getNumber();
    
    success = (new File(directoryName)).mkdir();
    if (!success) {
      log.error("Unable to create directory " + directoryName);      
    }
    
    String bioanalyzerDirName = directoryName + "\\" + Constants.QC_DIRECTORY;
    success = (new File(bioanalyzerDirName)).mkdir();
    if (!success) {
      log.error("Unable to create directory " + bioanalyzerDirName);      
    }
    
    if (req.getHybridizations() != null) {
      for(Iterator i = req.getHybridizations().iterator(); i.hasNext();) {
        Hybridization hyb = (Hybridization)i.next();
        String hybDirectoryName = directoryName + "\\" + hyb.getNumber();
        success = (new File(hybDirectoryName)).mkdir();
        if (!success) {
          log.error("Unable to create directory " + hybDirectoryName);      
        }
        
      }      
    }
  }
  
 
  
  
  

}