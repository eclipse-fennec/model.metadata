/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 * 
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 * 
 * SPDX-License-Identifier: EPL-2.0
 * 
 * Contributors:
 *   Data In Motion Consulting - initial implementation
 */
package org.eclipse.fennec.model.metadata.impl;

import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.common.notify.NotificationChain;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.InternalEObject;

import org.eclipse.emf.ecore.impl.ENotificationImpl;

import org.eclipse.emf.ecore.util.EcoreUtil;

import org.eclipse.fennec.model.metadata.MetadataPackage;
import org.eclipse.fennec.model.metadata.OperationAspect;
import org.eclipse.fennec.model.metadata.OperationMetadata;

/**
 * <!-- begin-user-doc -->
 * An implementation of the model object '<em><b>Operation Aspect</b></em>'.
 * <!-- end-user-doc -->
 * <p>
 * The following features are implemented:
 * </p>
 * <ul>
 *   <li>{@link org.eclipse.fennec.model.metadata.impl.OperationAspectImpl#getOperationMetadata <em>Operation Metadata</em>}</li>
 * </ul>
 *
 * @generated
 */
public abstract class OperationAspectImpl extends AspectImpl implements OperationAspect {
	/**
	 * <!-- begin-user-doc -->
	 * <!-- end-user-doc -->
	 * @generated
	 */
	protected OperationAspectImpl() {
		super();
	}

	/**
	 * <!-- begin-user-doc -->
	 * <!-- end-user-doc -->
	 * @generated
	 */
	@Override
	protected EClass eStaticClass() {
		return MetadataPackage.Literals.OPERATION_ASPECT;
	}

	/**
	 * <!-- begin-user-doc -->
	 * <!-- end-user-doc -->
	 * @generated
	 */
	@Override
	public OperationMetadata getOperationMetadata() {
		if (eContainerFeatureID() != MetadataPackage.OPERATION_ASPECT__OPERATION_METADATA) return null;
		return (OperationMetadata)eInternalContainer();
	}

	/**
	 * <!-- begin-user-doc -->
	 * <!-- end-user-doc -->
	 * @generated
	 */
	public NotificationChain basicSetOperationMetadata(OperationMetadata newOperationMetadata, NotificationChain msgs) {
		msgs = eBasicSetContainer((InternalEObject)newOperationMetadata, MetadataPackage.OPERATION_ASPECT__OPERATION_METADATA, msgs);
		return msgs;
	}

	/**
	 * <!-- begin-user-doc -->
	 * <!-- end-user-doc -->
	 * @generated
	 */
	@Override
	public void setOperationMetadata(OperationMetadata newOperationMetadata) {
		if (newOperationMetadata != eInternalContainer() || (eContainerFeatureID() != MetadataPackage.OPERATION_ASPECT__OPERATION_METADATA && newOperationMetadata != null)) {
			if (EcoreUtil.isAncestor(this, newOperationMetadata))
				throw new IllegalArgumentException("Recursive containment not allowed for " + toString());
			NotificationChain msgs = null;
			if (eInternalContainer() != null)
				msgs = eBasicRemoveFromContainer(msgs);
			if (newOperationMetadata != null)
				msgs = ((InternalEObject)newOperationMetadata).eInverseAdd(this, MetadataPackage.OPERATION_METADATA__ASPECTS, OperationMetadata.class, msgs);
			msgs = basicSetOperationMetadata(newOperationMetadata, msgs);
			if (msgs != null) msgs.dispatch();
		}
		else if (eNotificationRequired())
			eNotify(new ENotificationImpl(this, Notification.SET, MetadataPackage.OPERATION_ASPECT__OPERATION_METADATA, newOperationMetadata, newOperationMetadata));
	}

	/**
	 * <!-- begin-user-doc -->
	 * <!-- end-user-doc -->
	 * @generated
	 */
	@Override
	public NotificationChain eInverseAdd(InternalEObject otherEnd, int featureID, NotificationChain msgs) {
		switch (featureID) {
			case MetadataPackage.OPERATION_ASPECT__OPERATION_METADATA:
				if (eInternalContainer() != null)
					msgs = eBasicRemoveFromContainer(msgs);
				return basicSetOperationMetadata((OperationMetadata)otherEnd, msgs);
		}
		return super.eInverseAdd(otherEnd, featureID, msgs);
	}

	/**
	 * <!-- begin-user-doc -->
	 * <!-- end-user-doc -->
	 * @generated
	 */
	@Override
	public NotificationChain eInverseRemove(InternalEObject otherEnd, int featureID, NotificationChain msgs) {
		switch (featureID) {
			case MetadataPackage.OPERATION_ASPECT__OPERATION_METADATA:
				return basicSetOperationMetadata(null, msgs);
		}
		return super.eInverseRemove(otherEnd, featureID, msgs);
	}

	/**
	 * <!-- begin-user-doc -->
	 * <!-- end-user-doc -->
	 * @generated
	 */
	@Override
	public NotificationChain eBasicRemoveFromContainerFeature(NotificationChain msgs) {
		switch (eContainerFeatureID()) {
			case MetadataPackage.OPERATION_ASPECT__OPERATION_METADATA:
				return eInternalContainer().eInverseRemove(this, MetadataPackage.OPERATION_METADATA__ASPECTS, OperationMetadata.class, msgs);
		}
		return super.eBasicRemoveFromContainerFeature(msgs);
	}

	/**
	 * <!-- begin-user-doc -->
	 * <!-- end-user-doc -->
	 * @generated
	 */
	@Override
	public Object eGet(int featureID, boolean resolve, boolean coreType) {
		switch (featureID) {
			case MetadataPackage.OPERATION_ASPECT__OPERATION_METADATA:
				return getOperationMetadata();
		}
		return super.eGet(featureID, resolve, coreType);
	}

	/**
	 * <!-- begin-user-doc -->
	 * <!-- end-user-doc -->
	 * @generated
	 */
	@Override
	public void eSet(int featureID, Object newValue) {
		switch (featureID) {
			case MetadataPackage.OPERATION_ASPECT__OPERATION_METADATA:
				setOperationMetadata((OperationMetadata)newValue);
				return;
		}
		super.eSet(featureID, newValue);
	}

	/**
	 * <!-- begin-user-doc -->
	 * <!-- end-user-doc -->
	 * @generated
	 */
	@Override
	public void eUnset(int featureID) {
		switch (featureID) {
			case MetadataPackage.OPERATION_ASPECT__OPERATION_METADATA:
				setOperationMetadata((OperationMetadata)null);
				return;
		}
		super.eUnset(featureID);
	}

	/**
	 * <!-- begin-user-doc -->
	 * <!-- end-user-doc -->
	 * @generated
	 */
	@Override
	public boolean eIsSet(int featureID) {
		switch (featureID) {
			case MetadataPackage.OPERATION_ASPECT__OPERATION_METADATA:
				return getOperationMetadata() != null;
		}
		return super.eIsSet(featureID);
	}

} //OperationAspectImpl
