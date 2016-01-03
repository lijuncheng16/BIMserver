package org.bimserver.database.queries;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.bimserver.BimserverDatabaseException;
import org.bimserver.database.queries.om.CanInclude;
import org.bimserver.database.queries.om.Include;
import org.bimserver.database.queries.om.QueryPart;
import org.bimserver.shared.HashMapVirtualObject;
import org.bimserver.shared.QueryContext;
import org.bimserver.shared.QueryException;
import org.bimserver.shared.WrappedVirtualObject;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EReference;

public class QueryIncludeStackFrame extends DatabaseReadingStackFrame {

	private Set<Short> outputFilterCids;
	private Iterator<EReference> featureIterator;
	private Include include;
	private CanInclude previousInclude;
	private EReference feature;

	public QueryIncludeStackFrame(QueryObjectProvider queryObjectProvider, QueryContext queryContext, CanInclude previousInclude, Include include, HashMapVirtualObject currentObject, QueryPart queryPart) throws QueryException, BimserverDatabaseException {
		super(queryContext, queryObjectProvider, queryPart);
		this.previousInclude = previousInclude;
		this.include = include;
		this.currentObject = currentObject;
		
		List<EReference> features = include.getFields();
		if (features == null || features.isEmpty()) {
			setDone(true);
			return;
		}
		featureIterator = features.iterator();
		if (include.getOutputTypes() != null) {
			this.outputFilterCids = new HashSet<>();
			for (EClass eClass : include.getOutputTypes()) {
				short cid = queryObjectProvider.getDatabaseSession().getCidOfEClass(eClass);
				outputFilterCids.add(cid);
			}
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public boolean process() throws BimserverDatabaseException, QueryException {
		feature = featureIterator.next();
		Object value = currentObject.eGet(feature);
		if (value != null) {
			if (feature.isMany()) {
				List<Long> list = (List<Long>)value;
				for (Object r : list) {
					if (r instanceof Long) {
						processReference((Long)r);
					} else {
						// ??
					}
				}
			} else {
				if (value instanceof Long) {
					long refOid = (Long) value;
					processReference(refOid);
				} else if (value instanceof WrappedVirtualObject) {
					// ??
				}
			}
		}
		
		return !featureIterator.hasNext();
	}

	private void processReference(long refOid) {
		if (outputFilterCids == null || outputFilterCids.contains((short)refOid)) {
			if (!getQueryObjectProvider().hasRead(refOid)) {
				getQueryObjectProvider().push(new FollowReferenceStackFrame(getQueryObjectProvider(), refOid, getReusable(), getQueryPart(), feature, include));
			}
		}
	}
}