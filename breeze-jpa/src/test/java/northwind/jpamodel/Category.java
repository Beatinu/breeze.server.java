package northwind.jpamodel;

import java.util.Set;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Version;

@Entity
public class Category {
    private int categoryID;
    private String categoryName;
    private String description;
    private byte[] picture;
    private int rowVersion;

    private Set<Product> products;

    @Id
    @GeneratedValue(strategy=GenerationType.IDENTITY)
    public int getCategoryID() {
		return categoryID;
	}

	public void setCategoryID(int categoryID) {
		this.categoryID = categoryID;
	}
	@Column(length=15, nullable=false)
	public String getCategoryName() {
		return categoryName;
	}

	public void setCategoryName(String categoryName) {
		this.categoryName = categoryName;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public byte[] getPicture() {
		return picture;
	}

	public void setPicture(byte[] picture) {
		this.picture = picture;
	}

    @Version
	public int getRowVersion() {
		return rowVersion;
	}

	public void setRowVersion(int rowVersion) {
		this.rowVersion = rowVersion;
	}

	public Set<Product> getProducts() {
		return products;
	}

	public void setProducts(Set<Product> products) {
		this.products = products;
	}

}
