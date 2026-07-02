package org.openmrs.module.artemis.jaas;

import java.security.Principal;

public class RolePrincipal implements Principal {
	
	private final String name;
	
	public RolePrincipal(String name) {
		this.name = name;
	}
	
	@Override
	public String getName() {
		return name;
	}
	
	@Override
	public String toString() {
		return "RolePrincipal{" + name + '}';
	}
	
	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		RolePrincipal that = (RolePrincipal) o;
		return name.equals(that.name);
	}
	
	@Override
	public int hashCode() {
		return name.hashCode();
	}
}
