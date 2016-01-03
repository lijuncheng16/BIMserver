package org.bimserver.shared;

import java.nio.ByteBuffer;

import org.bimserver.BimserverDatabaseException;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EStructuralFeature;

public interface MinimalVirtualObject {
	EClass eClass();
	void setAttribute(EStructuralFeature eStructuralFeature, Object value) throws BimserverDatabaseException;
	ByteBuffer write() throws BimserverDatabaseException;
	Object eGet(EStructuralFeature feature);
	boolean useFeatureForSerialization(EStructuralFeature feature);
	boolean useFeatureForSerialization(EStructuralFeature feature, int index);
}
