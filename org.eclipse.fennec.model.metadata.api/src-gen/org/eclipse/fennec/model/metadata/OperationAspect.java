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
package org.eclipse.fennec.model.metadata;

import org.osgi.annotation.versioning.ProviderType;

/**
 * <!-- begin-user-doc -->
 * A representation of the model object '<em><b>Operation Aspect</b></em>'.
 * <!-- end-user-doc -->
 *
 * <!-- begin-model-doc -->
 * Base class for aspects attached to OperationMetadata. Provides a bidirectional reference to the owning OperationMetadata, enabling navigation from the aspect back to the metadata context and up to the class level.
 * <!-- end-model-doc -->
 *
 * <p>
 * The following features are supported:
 * </p>
 * <ul>
 *   <li>{@link org.eclipse.fennec.model.metadata.OperationAspect#getOperationMetadata <em>Operation Metadata</em>}</li>
 * </ul>
 *
 * @see org.eclipse.fennec.model.metadata.MetadataPackage#getOperationAspect()
 * @model abstract="true"
 * @generated
 */
@ProviderType
public interface OperationAspect extends Aspect {
	/**
	 * Returns the value of the '<em><b>Operation Metadata</b></em>' container reference.
	 * It is bidirectional and its opposite is '{@link org.eclipse.fennec.model.metadata.OperationMetadata#getAspects <em>Aspects</em>}'.
	 * <!-- begin-user-doc -->
	 * <!-- end-user-doc -->
	 * <!-- begin-model-doc -->
	 * The OperationMetadata that contains this aspect. Bidirectional opposite of OperationMetadata.aspects. Use operationMetadata.getEOperation() to access the original EOperation. Navigate operationMetadata.getClassMetadata() to reach the owning class.
	 * <!-- end-model-doc -->
	 * @return the value of the '<em>Operation Metadata</em>' container reference.
	 * @see #setOperationMetadata(OperationMetadata)
	 * @see org.eclipse.fennec.model.metadata.MetadataPackage#getOperationAspect_OperationMetadata()
	 * @see org.eclipse.fennec.model.metadata.OperationMetadata#getAspects
	 * @model opposite="aspects" transient="false"
	 * @generated
	 */
	OperationMetadata getOperationMetadata();

	/**
	 * Sets the value of the '{@link org.eclipse.fennec.model.metadata.OperationAspect#getOperationMetadata <em>Operation Metadata</em>}' container reference.
	 * <!-- begin-user-doc -->
	 * <!-- end-user-doc -->
	 * @param value the new value of the '<em>Operation Metadata</em>' container reference.
	 * @see #getOperationMetadata()
	 * @generated
	 */
	void setOperationMetadata(OperationMetadata value);

} // OperationAspect
