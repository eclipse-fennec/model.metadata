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

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.EMap;

import org.eclipse.emf.ecore.EPackage;

import org.osgi.annotation.versioning.ProviderType;

/**
 * <!-- begin-user-doc -->
 * A representation of the model object '<em><b>Package Metadata</b></em>'.
 * <!-- end-user-doc -->
 *
 * <!-- begin-model-doc -->
 * Pre-computed metadata for an EPackage. Root of the metadata tree for a package, containing ClassMetadata for all EClasses, aspects from all registered AspectProviders, and profiles built by providers. Created by MetadataService.registerPackage() and indexed for fast lookups.
 * <!-- end-model-doc -->
 *
 * <p>
 * The following features are supported:
 * </p>
 * <ul>
 *   <li>{@link org.eclipse.fennec.model.metadata.PackageMetadata#getEPackage <em>EPackage</em>}</li>
 *   <li>{@link org.eclipse.fennec.model.metadata.PackageMetadata#getNsURI <em>Ns URI</em>}</li>
 *   <li>{@link org.eclipse.fennec.model.metadata.PackageMetadata#getModelFingerprint <em>Model Fingerprint</em>}</li>
 *   <li>{@link org.eclipse.fennec.model.metadata.PackageMetadata#getClasses <em>Classes</em>}</li>
 *   <li>{@link org.eclipse.fennec.model.metadata.PackageMetadata#getAspects <em>Aspects</em>}</li>
 *   <li>{@link org.eclipse.fennec.model.metadata.PackageMetadata#getProfiles <em>Profiles</em>}</li>
 *   <li>{@link org.eclipse.fennec.model.metadata.PackageMetadata#getProperties <em>Properties</em>}</li>
 * </ul>
 *
 * @see org.eclipse.fennec.model.metadata.MetadataPackage#getPackageMetadata()
 * @model
 * @generated
 */
@ProviderType
public interface PackageMetadata extends DiagnosticContainer {
	/**
	 * Returns the value of the '<em><b>EPackage</b></em>' reference.
	 * <!-- begin-user-doc -->
	 * <!-- end-user-doc -->
	 * <!-- begin-model-doc -->
	 * The EPackage this metadata describes.
	 * <!-- end-model-doc -->
	 * @return the value of the '<em>EPackage</em>' reference.
	 * @see #setEPackage(EPackage)
	 * @see org.eclipse.fennec.model.metadata.MetadataPackage#getPackageMetadata_EPackage()
	 * @model
	 * @generated
	 */
	EPackage getEPackage();

	/**
	 * Sets the value of the '{@link org.eclipse.fennec.model.metadata.PackageMetadata#getEPackage <em>EPackage</em>}' reference.
	 * <!-- begin-user-doc -->
	 * <!-- end-user-doc -->
	 * @param value the new value of the '<em>EPackage</em>' reference.
	 * @see #getEPackage()
	 * @generated
	 */
	void setEPackage(EPackage value);

	/**
	 * Returns the value of the '<em><b>Ns URI</b></em>' attribute.
	 * <!-- begin-user-doc -->
	 * <!-- end-user-doc -->
	 * <!-- begin-model-doc -->
	 * Cached namespace URI of the EPackage. Used for fast lookup without dereferencing the EPackage.
	 * <!-- end-model-doc -->
	 * @return the value of the '<em>Ns URI</em>' attribute.
	 * @see #setNsURI(String)
	 * @see org.eclipse.fennec.model.metadata.MetadataPackage#getPackageMetadata_NsURI()
	 * @model
	 * @generated
	 */
	String getNsURI();

	/**
	 * Sets the value of the '{@link org.eclipse.fennec.model.metadata.PackageMetadata#getNsURI <em>Ns URI</em>}' attribute.
	 * <!-- begin-user-doc -->
	 * <!-- end-user-doc -->
	 * @param value the new value of the '<em>Ns URI</em>' attribute.
	 * @see #getNsURI()
	 * @generated
	 */
	void setNsURI(String value);

	/**
	 * Returns the value of the '<em><b>Model Fingerprint</b></em>' attribute.
	 * <!-- begin-user-doc -->
	 * <!-- end-user-doc -->
	 * <!-- begin-model-doc -->
	 * Canonical fingerprint of the EPackage model version (see FingerprintService), computed locally at registration. Cached derived value: it is the join key that links derived artifacts to their source model version (e.g. for orphan housekeeping). Distinct from the artifact store/reuse key, which additionally folds in derivation inputs.
	 * <!-- end-model-doc -->
	 * @return the value of the '<em>Model Fingerprint</em>' attribute.
	 * @see #setModelFingerprint(String)
	 * @see org.eclipse.fennec.model.metadata.MetadataPackage#getPackageMetadata_ModelFingerprint()
	 * @model
	 * @generated
	 */
	String getModelFingerprint();

	/**
	 * Sets the value of the '{@link org.eclipse.fennec.model.metadata.PackageMetadata#getModelFingerprint <em>Model Fingerprint</em>}' attribute.
	 * <!-- begin-user-doc -->
	 * <!-- end-user-doc -->
	 * @param value the new value of the '<em>Model Fingerprint</em>' attribute.
	 * @see #getModelFingerprint()
	 * @generated
	 */
	void setModelFingerprint(String value);

	/**
	 * Returns the value of the '<em><b>Classes</b></em>' containment reference list.
	 * The list contents are of type {@link org.eclipse.fennec.model.metadata.ClassMetadata}.
	 * It is bidirectional and its opposite is '{@link org.eclipse.fennec.model.metadata.ClassMetadata#getPackage <em>Package</em>}'.
	 * <!-- begin-user-doc -->
	 * <!-- end-user-doc -->
	 * <!-- begin-model-doc -->
	 * Metadata for all EClasses in this package. Bidirectional: each ClassMetadata has a back-reference via ClassMetadata.package.
	 * <!-- end-model-doc -->
	 * @return the value of the '<em>Classes</em>' containment reference list.
	 * @see org.eclipse.fennec.model.metadata.MetadataPackage#getPackageMetadata_Classes()
	 * @see org.eclipse.fennec.model.metadata.ClassMetadata#getPackage
	 * @model opposite="package" containment="true"
	 * @generated
	 */
	EList<ClassMetadata> getClasses();

	/**
	 * Returns the value of the '<em><b>Aspects</b></em>' containment reference list.
	 * The list contents are of type {@link org.eclipse.fennec.model.metadata.PackageAspect}.
	 * It is bidirectional and its opposite is '{@link org.eclipse.fennec.model.metadata.PackageAspect#getPackageMetadata <em>Package Metadata</em>}'.
	 * <!-- begin-user-doc -->
	 * <!-- end-user-doc -->
	 * <!-- begin-model-doc -->
	 * Aspects attached to this package by registered AspectProviders. One aspect per provider (identified by typeId). Bidirectional: each PackageAspect has a back-reference via PackageAspect.packageMetadata.
	 * <!-- end-model-doc -->
	 * @return the value of the '<em>Aspects</em>' containment reference list.
	 * @see org.eclipse.fennec.model.metadata.MetadataPackage#getPackageMetadata_Aspects()
	 * @see org.eclipse.fennec.model.metadata.PackageAspect#getPackageMetadata
	 * @model opposite="packageMetadata" containment="true"
	 * @generated
	 */
	EList<PackageAspect> getAspects();

	/**
	 * Returns the value of the '<em><b>Profiles</b></em>' containment reference list.
	 * The list contents are of type {@link org.eclipse.fennec.model.metadata.PackageProfile}.
	 * <!-- begin-user-doc -->
	 * <!-- end-user-doc -->
	 * <!-- begin-model-doc -->
	 * Pre-computed profiles built by AspectProviders. One profile per provider (identified by typeId). Profiles contain the fully resolved annotation-layer configuration for all classes. Built after all metadata, aspects, and cross-references are complete.
	 * <!-- end-model-doc -->
	 * @return the value of the '<em>Profiles</em>' containment reference list.
	 * @see org.eclipse.fennec.model.metadata.MetadataPackage#getPackageMetadata_Profiles()
	 * @model containment="true"
	 * @generated
	 */
	EList<PackageProfile> getProfiles();

	/**
	 * Returns the value of the '<em><b>Properties</b></em>' map.
	 * The key is of type {@link java.lang.String},
	 * and the value is of type {@link java.lang.String},
	 * <!-- begin-user-doc -->
	 * <!-- end-user-doc -->
	 * <!-- begin-model-doc -->
	 * Transient, runtime-only build context: the OSGi service properties of the incoming EPackage service, captured at registration. Not serialized and not replicated (service properties are node-specific and volatile) - it lets AspectProviders decide relevance and read hints during build. Values are stringified.
	 * <!-- end-model-doc -->
	 * @return the value of the '<em>Properties</em>' map.
	 * @see org.eclipse.fennec.model.metadata.MetadataPackage#getPackageMetadata_Properties()
	 * @model mapType="org.eclipse.emf.ecore.EStringToStringMapEntry&lt;org.eclipse.emf.ecore.EString, org.eclipse.emf.ecore.EString&gt;" transient="true"
	 * @generated
	 */
	EMap<String, String> getProperties();

} // PackageMetadata
