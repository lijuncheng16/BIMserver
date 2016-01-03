package org.bimserver.shared;

import java.nio.ByteBuffer;

import org.bimserver.BimserverDatabaseException;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EStructuralFeature;

public class ByteBufferWrappedVirtualObject extends AbstractByteBufferVirtualObject implements WrappedVirtualObject {

	private EClass eClass;

	public ByteBufferWrappedVirtualObject(QueryContext reusable, EClass eClass) {
		super(10);
		this.eClass = eClass;
		buffer.putShort((short) -reusable.getDatabaseInterface().getCidOfEClass(eClass));
	}

	@Override
	public EClass eClass() {
		return eClass;
	}

	@Override
	public void setAttribute(EStructuralFeature eStructuralFeature, Object value) throws BimserverDatabaseException {
		writePrimitiveValue(eStructuralFeature, value);
	}

	public ByteBuffer write() throws BimserverDatabaseException {
		return buffer;
	}

	@Override
	public Object eGet(EStructuralFeature feature) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean useFeatureForSerialization(EStructuralFeature feature) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void set(String name, Object value) throws BimserverDatabaseException {
		setAttribute(eClass.getEStructuralFeature(name), value);
	}

	@Override
	public int getSize() {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean useFeatureForSerialization(EStructuralFeature feature, int index) {
		// TODO Auto-generated method stub
		return false;
	}
}