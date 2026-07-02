package org.openmrs.module.artemis.jaas;

import java.security.Principal;

public class UserPrincipal implements Principal {
	
	private final String name;
	
	public UserPrincipal(String name) {
		this.name = name;
	}
	
	@Override
	public String getName() {
		return name;
	}
	
	@Override
	public String toString() {
		return "UserPrincipal{" + name + '}';
	}
	
	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		UserPrincipal that = (UserPrincipal) o;
		return name.equals(that.name);
	}
	
	@Override
	public int hashCode() {
		return name.hashCode();
	}
}
