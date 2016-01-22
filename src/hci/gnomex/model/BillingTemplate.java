package hci.gnomex.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.hibernate.Session;
import org.jdom.Element;

import hci.framework.utilities.XMLReflectException;
import hci.gnomex.controller.SaveBillingTemplate;
import hci.gnomex.utility.BillingItemQueryManager;
import hci.gnomex.utility.DetailObject;
import hci.gnomex.utility.Order;
import hci.gnomex.utility.QueryManager;
import hci.gnomex.utility.XMLTools;
import hci.hibernate3utils.HibernateDetailObject;

@SuppressWarnings("serial")
public class BillingTemplate extends HibernateDetailObject implements DetailObject {
	
	private Integer 					idBillingTemplate;
	private Integer						targetClassIdentifier;
	private String						targetClassName;
	private Set<BillingTemplateItem> 	items;
	private Set<MasterBillingItem>		masterBillingItems;
	
	public BillingTemplate() {
		super();
		
		this.setItems(new TreeSet<BillingTemplateItem>());
		this.setMasterBillingItems(new TreeSet<MasterBillingItem>());
	}
	
	public BillingTemplate(Order order) {
		this();
		
		this.setOrder(order);
	}
	
	public void setIdBillingAccount(Integer idBillingAccount) {
		this.setItems(new TreeSet<BillingTemplateItem>());
		BillingTemplateItem item = new BillingTemplateItem(this);
		item.setIdBillingAccount(idBillingAccount);
		item.setPercentSplit(BillingTemplateItem.WILL_TAKE_REMAINING_BALANCE);
		item.setSortOrder(1);
		this.getItems().add(item);
	}
	
	public Set<BillingItem> getBillingItems(Session sess) {
		return BillingItemQueryManager.getBillingItemsForBillingTemplate(sess, this.idBillingTemplate);
	}
	
	public void recreateBillingItems(Session sess) {
		// Apply new template to all master billing items
		for (MasterBillingItem masterBillingItem : this.getMasterBillingItems()) {
			SaveBillingTemplate.createBillingItemsForMaster(sess, masterBillingItem, this);
		}
	}
	
	public void setOrder(Order order) {
		this.setTargetClassIdentifier(order.getTargetClassIdentifier());
		this.setTargetClassName(order.getTargetClassName());
	}
	
	public List<BillingAccount> getBillingAccounts(Session sess) {
		Set<BillingTemplateItem> items = this.getItems();
		ArrayList<BillingAccount> accounts = new ArrayList<BillingAccount>();
		
		if (items != null) {
			for (BillingTemplateItem item : items) {
				accounts.add((BillingAccount) sess.load(BillingAccount.class, item.getIdBillingAccount()));
			}
		}
		
		return accounts;
	}
	
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((this.idBillingTemplate == null) ? 0 : this.idBillingTemplate.hashCode());
		return result;
	}
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (!(obj instanceof BillingTemplate))
			return false;
		BillingTemplate other = (BillingTemplate) obj;
		if (this.idBillingTemplate == null) {
			if (other.idBillingTemplate != null)
				return false;
		} else if (!this.idBillingTemplate.equals(other.idBillingTemplate))
			return false;
		return true;
	}
	public Integer getIdBillingTemplate() {
		return this.idBillingTemplate;
	}
	public void setIdBillingTemplate(Integer idBillingTemplate) {
		this.idBillingTemplate = idBillingTemplate;
	}
	public Integer getTargetClassIdentifier() {
		return this.targetClassIdentifier;
	}
	public void setTargetClassIdentifier(Integer targetClassIdentifier) {
		this.targetClassIdentifier = targetClassIdentifier;
	}
	public String getTargetClassName() {
		return this.targetClassName;
	}
	public void setTargetClassName(String targetClassName) {
		this.targetClassName = targetClassName;
	}
	public Set<BillingTemplateItem> getItems() {
		return this.items;
	}
	public void setItems(Set<BillingTemplateItem> items) {
		this.items = items;
	}
	public Set<MasterBillingItem> getMasterBillingItems() {
		return this.masterBillingItems;
	}
	public void setMasterBillingItems(Set<MasterBillingItem> masterBillingItems) {
		this.masterBillingItems = masterBillingItems;
	}

	@Override
	public Element toXML(Set<String> detailParameters) {
		Element billingTemplateNode = new Element("BillingTemplate");
		
		billingTemplateNode.setAttribute("idBillingTemplate", XMLTools.safeXMLValue(this.getIdBillingTemplate()));
		billingTemplateNode.setAttribute("targetClassIdentifier", XMLTools.safeXMLValue(this.getTargetClassIdentifier()));
		billingTemplateNode.setAttribute("targetClassName", XMLTools.safeXMLValue(this.getTargetClassName()));
		
		for (BillingTemplateItem item : this.getItems()) {
			billingTemplateNode.addContent(item.toXML(null));
		}
		
		return billingTemplateNode;
	}

}