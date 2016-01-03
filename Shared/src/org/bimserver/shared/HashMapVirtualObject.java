package org.bimserver.shared;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bimserver.BimserverDatabaseException;
import org.bimserver.emf.PackageMetaData;
import org.bimserver.models.ifc2x3tc1.Tristate;
import org.bimserver.plugins.deserializers.DatabaseInterface;
import org.eclipse.emf.common.util.Enumerator;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EDataType;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.EcorePackage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;

public class HashMapVirtualObject extends AbstractHashMapVirtualObject implements VirtualObject {
	private static final Logger LOGGER = LoggerFactory.getLogger(VirtualObject.class);
	private final Map<EStructuralFeature, Object> map = new HashMap<>();
	private EClass eClass;
	private long oid;
	private QueryContext reusable;
	private Map<EStructuralFeature, Object> useForSerializationFeatures = new HashMap<>();
	
	public HashMapVirtualObject(QueryContext reusable, EClass eClass) {
		this.reusable = reusable;
		this.eClass = eClass;
		this.oid = reusable.getDatabaseInterface().newOid(eClass);
	}

	public void eUnset(EStructuralFeature feature) {
		map.remove(feature);
	}

	public void setAttribute(EStructuralFeature feature, Object val) {
		map.put(feature, val);
	}

	public Object eGet(EStructuralFeature feature) {
		return map.get(feature);
	}

	public Object get(String featureName) {
		EStructuralFeature eStructuralFeature = eClass.getEStructuralFeature(featureName);
		return map.get(eStructuralFeature);
	}
	
	public boolean eIsSet(EStructuralFeature feature) {
		return map.containsKey(feature);
	}
	
	public EClass eClass() {
		return eClass;
	}
	
	private int getWrappedValueSize(Object val, EReference eReference) {
		if (val == null) {
			return 2;
		}
		if (val instanceof VirtualObject) {
			VirtualObject eObject = (VirtualObject) val;
			int refSize = 10;
			if (eReference.getEAnnotation("twodimensionalarray") != null) {
				refSize = 4;
				EStructuralFeature eStructuralFeature = eObject.eClass().getEStructuralFeature("List");
				List<?> l = (List<?>)eObject.eGet(eStructuralFeature);
				for (Object o : l) {
					if (o instanceof VirtualObject) {
						refSize += 10;
					} else {
						refSize += getPrimitiveSize((EDataType) eStructuralFeature.getEType(), o);
					}
				}
			}
			if (eObject.eClass().getEAnnotation("wrapped") != null) {
				VirtualObject wrappedValue = (VirtualObject) val;
				EStructuralFeature wrappedValueFeature = wrappedValue.eClass().getEStructuralFeature("wrappedValue");
				Object wrappedVal = eObject.eGet(wrappedValueFeature);
				refSize = 2 + getPrimitiveSize((EDataType) wrappedValueFeature.getEType(), wrappedVal);
			}
			return refSize;
		} else if (val instanceof WrappedVirtualObject) {
			WrappedVirtualObject wrappedVirtualObject = (WrappedVirtualObject)val;
			return wrappedVirtualObject.getSize();
		}
		return 10;
	}

	private int getExactSize(VirtualObject virtualObject) {
		int size = 0;

		for (EStructuralFeature eStructuralFeature : eClass().getEAllStructuralFeatures()) {
			if (getPackageMetaData().useForDatabaseStorage(eClass, eStructuralFeature)) {
				if (!useUnsetBit(eStructuralFeature)) {
					Object val = eGet(eStructuralFeature);
					if (eStructuralFeature instanceof EAttribute) {
						EAttribute eAttribute = (EAttribute) eStructuralFeature;
						if (eAttribute.isMany()) {
							size += 4;
							for (Object v : ((List<?>) val)) {
								size += getPrimitiveSize(eAttribute.getEAttributeType(), v);
							}
						} else {
							size += getPrimitiveSize(eAttribute.getEAttributeType(), val);
						}
					} else if (eStructuralFeature instanceof EReference) {
						EReference eReference = (EReference) eStructuralFeature;
						if (eReference.isMany()) {
							size += 4;
							for (Object v : ((List<?>) val)) {
								size += getWrappedValueSize(v, eReference);
							}
						} else {
							if (val == null) {
								size += 2;
							} else {
								size += getWrappedValueSize(val, eReference);
							}
						}
					}
				}
			}
		}

		size += getPackageMetaData().getUnsettedLength(eClass);

		return size;
	}
	
	private boolean useUnsetBit(EStructuralFeature feature) {
		// TODO non-unsettable boolean values can also be stored in these bits
		Object value = eGet(feature);
		if (feature.isUnsettable()) {
			if (!eIsSet(feature)) {
				return true;
			}
		} else {
			if (feature.isMany() && (value == null || ((List<?>)value).isEmpty())) {
				return true;
			}
			if (feature.getDefaultValue() == value || (feature.getDefaultValue() != null && feature.getDefaultValue().equals(value))) {
				return true;
			}
		}
		return false;
	}

	public long getOid() {
		return oid;
	}
	
	public ByteBuffer write() throws BimserverDatabaseException {
		int bufferSize = getExactSize(this);
		ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
		byte[] unsetted = new byte[getPackageMetaData().getUnsettedLength(eClass)];
		int fieldCounter = 0;
		for (EStructuralFeature feature : eClass().getEAllStructuralFeatures()) {
			if (getPackageMetaData().useForDatabaseStorage(eClass, feature)) {
				if (useUnsetBit(feature)) {
					unsetted[fieldCounter / 8] |= (1 << (fieldCounter % 8));
				}
				fieldCounter++;
			}
		}
		buffer.put(unsetted);
		
		EClass eClass = getDatabaseInterface().getEClassForOid(getOid());
		if (!eClass.isSuperTypeOf(eClass())) {
			throw new BimserverDatabaseException("Object with oid " + getOid() + " is a " + eClass().getName() + " but it's cid-part says it's a " + eClass.getName());
		}

		for (EStructuralFeature feature : eClass().getEAllStructuralFeatures()) {
			if (getPackageMetaData().useForDatabaseStorage(eClass, feature)) {
				if (!useUnsetBit(feature)) {
					if (feature.isMany()) {
						writeList(this, buffer, getPackageMetaData(), feature);
					} else {
						Object value = eGet(feature);
						if (feature.getEType() instanceof EEnum) {
							if (value == null) {
								buffer.putInt(-1);
							} else {
								EEnum eEnum = (EEnum) feature.getEType();
								EEnumLiteral eEnumLiteral = eEnum.getEEnumLiteralByLiteral(((Enum<?>) value).toString());
								if (eEnumLiteral != null) {
									buffer.putInt(eEnumLiteral.getValue());
								} else {
									LOGGER.error(((Enum<?>) value).toString() + " not found");
									buffer.putInt(-1);
								}
							}
						} else if (feature.getEType() instanceof EClass) {
							if (value == null) {
								buffer.putShort((short) -1);
							} else if (value instanceof VirtualObject) {
								writeWrappedValue(getPid(), getRid(), (VirtualObject) value, buffer, getPackageMetaData());
							} else if (value instanceof WrappedVirtualObject) {
								writeWrappedValue(getPid(), getRid(), (WrappedVirtualObject) value, buffer, getPackageMetaData());
							} else {
								long referencedOid = (Long)value;
								writeReference(referencedOid, buffer, feature);
							}
						} else if (feature.getEType() instanceof EDataType) {
							writePrimitiveValue(feature, value, buffer);
						}
					}
				}
			}
		}
		if (buffer.position() != bufferSize) {
			throw new BimserverDatabaseException("Value buffer sizes do not match for " + eClass().getName() + " " + buffer.position() + "/" + bufferSize);
		}
		return buffer;
	}
	
	private PackageMetaData getPackageMetaData() {
		return reusable.getPackageMetaData();
	}

	private DatabaseInterface getDatabaseInterface() {
		return reusable.getDatabaseInterface();
	}

	public int getPid() {
		return reusable.getPid();
	}
	
	public int getRid() {
		return reusable.getRid();
	}
	
	private void writeWrappedValue(int pid, int rid, VirtualObject wrappedValue, ByteBuffer buffer, PackageMetaData packageMetaData) throws BimserverDatabaseException {
		EStructuralFeature eStructuralFeature = wrappedValue.eClass().getEStructuralFeature("wrappedValue");
		Short cid = getDatabaseInterface().getCidOfEClass(wrappedValue.eClass());
		buffer.putShort((short) -cid);
		writePrimitiveValue(eStructuralFeature, wrappedValue.eGet(eStructuralFeature), buffer);
		if (wrappedValue.eClass().getName().equals("IfcGloballyUniqueId")) {
			EClass eClass = packageMetaData.getEClass("IfcGloballyUniqueId");
			if (wrappedValue.getOid() == -1) {
				((VirtualObject) wrappedValue).setOid(getDatabaseInterface().newOid(eClass));
			}
			getDatabaseInterface().save(wrappedValue);
		}
	}

	private void writeWrappedValue(int pid, int rid, WrappedVirtualObject wrappedValue, ByteBuffer buffer, PackageMetaData packageMetaData) throws BimserverDatabaseException {
		Short cid = getDatabaseInterface().getCidOfEClass(wrappedValue.eClass());
		buffer.putShort((short) -cid);
		for (EStructuralFeature eStructuralFeature : wrappedValue.eClass().getEAllStructuralFeatures()) {
			writePrimitiveValue(eStructuralFeature, wrappedValue.eGet(eStructuralFeature), buffer);
		}
	}
	
	public void setOid(long oid) {
		this.oid = oid;
	}

	private void writeReference(long referenceOid, ByteBuffer buffer, EStructuralFeature feature) throws BimserverDatabaseException {
		Short cid = getDatabaseInterface().getCidOfEClass(getDatabaseInterface().getEClassForOid((referenceOid)));
		buffer.putShort(cid);
		if (referenceOid < 0) {
			throw new BimserverDatabaseException("Writing a reference with oid " + referenceOid + ", this is not supposed to happen, referenced: " + referenceOid + " " + referenceOid + " from " + getOid() + " " + this);
		}
		buffer.putLong(referenceOid);
	}

	@SuppressWarnings("rawtypes")
	private void writeList(VirtualObject virtualObject, ByteBuffer buffer, PackageMetaData packageMetaData, EStructuralFeature feature) throws BimserverDatabaseException {
		if (feature.getEType() instanceof EEnum) {
			// Aggregate relations to enums never occur... at this
			// moment
		} else if (feature.getEType() instanceof EClass) {
			List list = (List) virtualObject.eGet(feature);
			buffer.putInt(list.size());
			for (Object o : list) {
				if (o == null) {
					buffer.putShort((short) -1);
				} else {
					if (o instanceof VirtualObject) {
						VirtualObject listObject = (VirtualObject)o;
						if (feature.getEAnnotation("twodimensionalarray") != null) {
							EStructuralFeature lf = listObject.eClass().getEStructuralFeature("List");
							writeList(listObject, buffer, packageMetaData, lf);
						} else {
							if (listObject.eClass().getEAnnotation("wrapped") != null || listObject.eClass().getEStructuralFeature("wrappedValue") != null) {
								writeWrappedValue(getPid(), getRid(), listObject, buffer, packageMetaData);
							}
						}
					} else {
						long listObjectOid = (Long) o;
						writeReference(listObjectOid, buffer, feature);
					}
				}
			}
		} else if (feature.getEType() instanceof EDataType) {
			List list = (List) eGet(feature);
			buffer.putInt(list.size());
			for (Object o : list) {
				writePrimitiveValue(feature, o, buffer);
			}
		}
	}
	
	private void writePrimitiveValue(EStructuralFeature feature, Object value, ByteBuffer buffer) throws BimserverDatabaseException {
		EClassifier type = feature.getEType();
		if (type == EcorePackage.eINSTANCE.getEString()) {
			if (value == null) {
				buffer.putInt(-1);
			} else {
				String stringValue = (String) value;
				byte[] bytes = stringValue.getBytes(Charsets.UTF_8);
				if (bytes.length > Integer.MAX_VALUE) {
					throw new BimserverDatabaseException("String value too long (max length is " + Integer.MAX_VALUE + ")");
				}
				buffer.putInt(bytes.length);
				buffer.put(bytes);
			}
		} else if (type == EcorePackage.eINSTANCE.getEInt() || type == EcorePackage.eINSTANCE.getEIntegerObject()) {
			if (value == null) {
				buffer.putInt(0);
			} else {
				buffer.putInt((Integer) value);
			}
		} else if (type == EcorePackage.eINSTANCE.getEDouble() || type == EcorePackage.eINSTANCE.getEDoubleObject()) {
			if (value == null) {
				buffer.putDouble(0D);
			} else {
				buffer.putDouble((Double) value);
			}
		} else if (type == EcorePackage.eINSTANCE.getEFloat() || type == EcorePackage.eINSTANCE.getEFloatObject()) {
			if (value == null) {
				buffer.putFloat(0F);
			} else {
				buffer.putFloat((Float) value);
			}
		} else if (type == EcorePackage.eINSTANCE.getELong() || type == EcorePackage.eINSTANCE.getELongObject()) {
			if (value == null) {
				buffer.putLong(0L);
			} else {
				buffer.putLong((Long) value);
			}
		} else if (type == EcorePackage.eINSTANCE.getEBoolean() || type == EcorePackage.eINSTANCE.getEBooleanObject()) {
			if (value == null) {
				buffer.put((byte) 0);
			} else {
				buffer.put(((Boolean) value) ? (byte) 1 : (byte) 0);
			}
		} else if (type == EcorePackage.eINSTANCE.getEDate()) {
			if (value == null) {
				buffer.putLong(-1L);
			} else {
				buffer.putLong(((Date) value).getTime());
			}
		} else if (type.getName().equals("Tristate")) {
			if (value == null) {
				buffer.putInt(Tristate.UNDEFINED.getValue());
			} else {
				Enumerator eEnumLiteral = (Enumerator) value;
				buffer.putInt(eEnumLiteral.getValue());
			}
		} else if (value instanceof Enumerator) {
			Enumerator eEnumLiteral = (Enumerator) value;
			buffer.putInt(eEnumLiteral.getValue());
		} else if (type == EcorePackage.eINSTANCE.getEByteArray()) {
			if (value == null) {
				buffer.putInt(0);
			} else {
				byte[] bytes = (byte[]) value;
				buffer.putInt(bytes.length);
				buffer.put(bytes);
			}
		} else {
			throw new RuntimeException("Unsupported type " + type.getName());
		}
	}
	
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public void setListItem(EStructuralFeature structuralFeature, int index, Object value) {
		List list = getOrCreateList(structuralFeature, index + 1);
		list.set(index, value);
	}
	
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public void setListItemReference(EStructuralFeature structuralFeature, int index, EClass referenceEClass, Long referencedOid, int bufferPosition) {
		List list = getOrCreateList(structuralFeature, index + 1);
		list.set(index, referencedOid);
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private List getOrCreateList(EStructuralFeature structuralFeature, int minSize) {
		List list = (List<?>) map.get(structuralFeature);
		if (list == null) {
			list = new ArrayList(minSize == -1 ? 0 : minSize);
			map.put(structuralFeature, list);
		}
		while (list.size() < minSize) {
			list.add(null);
		}
		return list;
	}

	public void save() throws BimserverDatabaseException {
		getDatabaseInterface().save(this);
	}

	@Override
	public int reserveSpaceForReference(EStructuralFeature feature) {
		// Not relevant for HashMap based implementation
		return -1;
	}

	@Override
	public int reserveSpaceForListReference() {
		// Not relevant for HashMap based implementation
		return -1;
	}
	
	@Override
	public void startList(EStructuralFeature feature) {
		// Not relevant for HashMap based implementation		
	}

	@Override
	public void setReference(EStructuralFeature feature, long referenceOid, int bufferPosition) throws BimserverDatabaseException {
		map.put(feature, referenceOid);
	}

	@Override
	public void endList() {
		// Not relevant for HashMap based implementation		
	}

	public long getRoid() {
		return reusable.getRoid();
	}

	public boolean has(String key) {
		EStructuralFeature eStructuralFeature = eClass.getEStructuralFeature(key);
		return map.containsKey(eStructuralFeature);
	}

	@SuppressWarnings("unchecked")
	public boolean useFeatureForSerialization(EStructuralFeature feature, int index) {
		if (feature instanceof EAttribute) {
			return true;
		}
		if (useForSerializationFeatures.containsKey(feature)) {
			Object object = useForSerializationFeatures.get(feature);
			if (object instanceof Set) {
				Set<Integer> set = (Set<Integer>) object;
				if (set.contains(index)) {
					return true;
				}
			} else {
				return object == Boolean.TRUE;
			}
		}
		return false;
	}
	
	public boolean useFeatureForSerialization(EStructuralFeature feature) {
		if (feature instanceof EAttribute) {
			return true;
		}
		return useForSerializationFeatures.containsKey(feature);
	}

	public void addUseForSerialization(EStructuralFeature eStructuralFeature) {
		if (eStructuralFeature.getEContainingClass().isSuperTypeOf(eClass)) {
			useForSerializationFeatures.put(eStructuralFeature, true);
		} else {
			throw new IllegalArgumentException(eStructuralFeature.getName() + " does not exist in " + eClass.getName());
		}
	}
	
	@SuppressWarnings("unchecked")
	public void addUseForSerialization(EStructuralFeature eStructuralFeature, int index) {
		if (eStructuralFeature.getEContainingClass().isSuperTypeOf(eClass)) {
			Set<Integer> set = (Set<Integer>) useForSerializationFeatures.get(eStructuralFeature);
			if (set == null) {
				set = new HashSet<>();
				useForSerializationFeatures.put(eStructuralFeature, set);
			}
			set.add(index);
		} else {
			throw new IllegalArgumentException(eStructuralFeature.getName() + " does not exist in " + eClass.getName());
		}
	}

	public void saveOverwrite() throws BimserverDatabaseException {
		getDatabaseInterface().saveOverwrite(this);
	}

	@Override
	public void set(String name, Object val) throws BimserverDatabaseException {
		setAttribute(eClass.getEStructuralFeature(name), val);
	}
}
