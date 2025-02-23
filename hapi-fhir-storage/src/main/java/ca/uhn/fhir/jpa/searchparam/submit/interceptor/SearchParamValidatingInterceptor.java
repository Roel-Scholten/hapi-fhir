package ca.uhn.fhir.jpa.searchparam.submit.interceptor;

/*-
 * #%L
 * HAPI FHIR Storage api
 * %%
 * Copyright (C) 2014 - 2022 Smile CDR, Inc.
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeSearchParam;
import ca.uhn.fhir.i18n.Msg;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.api.svc.IIdHelperService;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.jpa.searchparam.registry.SearchParameterCanonicalizer;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.storage.ResourcePersistentId;
import ca.uhn.fhir.rest.param.TokenAndListParam;
import ca.uhn.fhir.rest.param.TokenOrListParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import org.apache.commons.lang3.Validate;
import org.hl7.fhir.instance.model.api.IBaseExtension;
import org.hl7.fhir.instance.model.api.IBaseHasExtensions;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IPrimitiveType;
import org.springframework.beans.factory.annotation.Autowired;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.apache.commons.collections4.CollectionUtils.isNotEmpty;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

@Interceptor
public class SearchParamValidatingInterceptor {

	public static final String SEARCH_PARAM = "SearchParameter";
	public List<String> myUpliftExtensions;

	private FhirContext myFhirContext;

	private SearchParameterCanonicalizer mySearchParameterCanonicalizer;

	private DaoRegistry myDaoRegistry;

	private IIdHelperService myIdHelperService;

	@Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_CREATED)
	public void resourcePreCreate(IBaseResource theResource, RequestDetails theRequestDetails) {
		validateSearchParamOnCreate(theResource, theRequestDetails);
	}

	@Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_UPDATED)
	public void resourcePreUpdate(IBaseResource theOldResource, IBaseResource theNewResource, RequestDetails theRequestDetails) {
		validateSearchParamOnUpdate(theNewResource, theRequestDetails);
	}

	public void validateSearchParamOnCreate(IBaseResource theResource, RequestDetails theRequestDetails){
		if(isNotSearchParameterResource(theResource)){
			return;
		}
		RuntimeSearchParam runtimeSearchParam = mySearchParameterCanonicalizer.canonicalizeSearchParameter(theResource);
		if (runtimeSearchParam == null) {
			return;
		}

		SearchParameterMap searchParameterMap = extractSearchParameterMap(runtimeSearchParam);
		if (searchParameterMap != null) {
			if (isUpliftSearchParam(theResource)) {
				validateUpliftSp(theRequestDetails, runtimeSearchParam, searchParameterMap);
			} else {
				validateStandardSpOnCreate(theRequestDetails, searchParameterMap);
			}
		}
	}

	private void validateStandardSpOnCreate(RequestDetails theRequestDetails, SearchParameterMap searchParameterMap) {
		List<ResourcePersistentId> persistedIdList = getDao().searchForIds(searchParameterMap, theRequestDetails);
		if( isNotEmpty(persistedIdList) ) {
			throw new UnprocessableEntityException(Msg.code(2196) + "Can't process submitted SearchParameter as it is overlapping an existing one.");
		}
	}

	public void validateSearchParamOnUpdate(IBaseResource theResource, RequestDetails theRequestDetails){
		if(isNotSearchParameterResource(theResource)){
			return;
		}
		RuntimeSearchParam runtimeSearchParam = mySearchParameterCanonicalizer.canonicalizeSearchParameter(theResource);
		if (runtimeSearchParam == null) {
			return;
		}

		SearchParameterMap searchParameterMap = extractSearchParameterMap(runtimeSearchParam);
		if (searchParameterMap != null) {
			if (isUpliftSearchParam(theResource)) {
				validateUpliftSp(theRequestDetails, runtimeSearchParam, searchParameterMap);
			} else {
				validateStandardSpOnUpdate(theRequestDetails, runtimeSearchParam, searchParameterMap);
			}
		}
	}

	private void validateUpliftSp(RequestDetails theRequestDetails, @Nonnull RuntimeSearchParam theRuntimeSearchParam, SearchParameterMap theSearchParameterMap) {
		Validate.notEmpty(getUpliftExtensions(), "You are attempting to validate an Uplift Search Parameter, but have not defined which URLs correspond to uplifted search parameter extensions.");

		IBundleProvider bundleProvider = getDao().search(theSearchParameterMap, theRequestDetails);
		List<IBaseResource> allResources = bundleProvider.getAllResources();
		if(isNotEmpty(allResources)) {
			Set<String> existingIds = allResources.stream().map(resource -> resource.getIdElement().getIdPart()).collect(Collectors.toSet());
			if (isNewSearchParam(theRuntimeSearchParam, existingIds)) {
				for (String upliftExtensionUrl: getUpliftExtensions()) {
					boolean matchesExistingUplift = allResources.stream()
						.map(sp -> mySearchParameterCanonicalizer.canonicalizeSearchParameter(sp))
						.filter(sp -> !sp.getExtensions(upliftExtensionUrl).isEmpty())
						.anyMatch(sp -> isDuplicateUpliftParameter(theRuntimeSearchParam, sp, upliftExtensionUrl));

					if (matchesExistingUplift) {
						throwDuplicateError();
					}
				}
			}
		}
	}

	private boolean isDuplicateUpliftParameter(RuntimeSearchParam theRuntimeSearchParam, RuntimeSearchParam theSp, String theUpliftUrl) {
		String firstCode = getUpliftChildExtensionValueByUrl(theRuntimeSearchParam, "code", theUpliftUrl);
		String secondCode = getUpliftChildExtensionValueByUrl(theSp, "code", theUpliftUrl);
		String firstElementName = getUpliftChildExtensionValueByUrl(theRuntimeSearchParam, "element-name", theUpliftUrl);
		String secondElementName = getUpliftChildExtensionValueByUrl(theSp, "element-name", theUpliftUrl);
		return firstCode.equals(secondCode) && firstElementName.equals(secondElementName);
	}


	private String getUpliftChildExtensionValueByUrl(RuntimeSearchParam theSp, String theUrl, String theUpliftUrl) {
		List<IBaseExtension<?, ?>> extensions = theSp.getExtensions(theUpliftUrl);
		Validate.isTrue(extensions.size() == 1);
		IBaseExtension<?, ?> topLevelExtension = extensions.get(0);
		List<IBaseExtension> extension = (List<IBaseExtension>) topLevelExtension.getExtension();
		String subExtensionValue = extension.stream().filter(ext -> ext.getUrl().equals(theUrl)).map(IBaseExtension::getValue)
			.map(IPrimitiveType.class::cast)
			.map(IPrimitiveType::getValueAsString)
			.findFirst()
			.orElseThrow(() -> new UnprocessableEntityException(Msg.code(2198), "Unable to process Uplift SP addition as the SearchParameter is malformed."));
		return subExtensionValue;
	}

	private boolean isNewSearchParam(RuntimeSearchParam theSearchParam, Set<String> theExistingIds) {
		return theExistingIds
			.stream()
			.noneMatch(resId -> resId.equals(theSearchParam.getId().getIdPart()));
	}

	private void validateStandardSpOnUpdate(RequestDetails theRequestDetails, RuntimeSearchParam runtimeSearchParam, SearchParameterMap searchParameterMap) {
		List<ResourcePersistentId> pidList = getDao().searchForIds(searchParameterMap, theRequestDetails);
		if(isNotEmpty(pidList)){
			Set<String> resolvedResourceIds = myIdHelperService.translatePidsToFhirResourceIds(new HashSet<>(pidList));
			if(isNewSearchParam(runtimeSearchParam, resolvedResourceIds)) {
				throwDuplicateError();
			}
		}
	}

	private void throwDuplicateError() {
		throw new UnprocessableEntityException(Msg.code(2125) + "Can't process submitted SearchParameter as it is overlapping an existing one.");
	}

	private boolean isUpliftSearchParam(IBaseResource theResource) {
		if (theResource instanceof IBaseHasExtensions) {
			IBaseHasExtensions resource = (IBaseHasExtensions) theResource;
			return resource.getExtension()
				.stream()
				.anyMatch(ext -> getUpliftExtensions().contains(ext.getUrl()));
		} else {
			return false;
		}
	}

	private boolean isNotSearchParameterResource(IBaseResource theResource){
		return ! SEARCH_PARAM.equalsIgnoreCase(myFhirContext.getResourceType(theResource));
	}

	@Nullable
	private SearchParameterMap extractSearchParameterMap(RuntimeSearchParam theRuntimeSearchParam) {
		SearchParameterMap retVal = new SearchParameterMap();

		String code = theRuntimeSearchParam.getName();
		List<String> theBases = List.copyOf(theRuntimeSearchParam.getBase());
		if (isBlank(code) || theBases.isEmpty()) {
			return null;
		}

		TokenAndListParam codeParam = new TokenAndListParam().addAnd(new TokenParam(code));
		TokenAndListParam basesParam = toTokenAndList(theBases);

		retVal.add("code", codeParam);
		retVal.add("base", basesParam);

		return retVal;
	}

	@Autowired
	public void setFhirContext(FhirContext theFhirContext) {
		myFhirContext = theFhirContext;
	}

	@Autowired
	public void setSearchParameterCanonicalizer(SearchParameterCanonicalizer theSearchParameterCanonicalizer) {
		mySearchParameterCanonicalizer = theSearchParameterCanonicalizer;
	}

	@Autowired
	public void setDaoRegistry(DaoRegistry theDaoRegistry) {
		myDaoRegistry = theDaoRegistry;
	}

	@Autowired
	public void setIIDHelperService(IIdHelperService theIdHelperService) {
		myIdHelperService = theIdHelperService;
	}

	private IFhirResourceDao getDao() {
		return myDaoRegistry.getResourceDao(SEARCH_PARAM);
	}

	private TokenAndListParam toTokenAndList(List<String> theBases) {
		TokenAndListParam retVal = new TokenAndListParam();

		if (theBases != null) {

			TokenOrListParam tokenOrListParam = new TokenOrListParam();
			retVal.addAnd(tokenOrListParam);

			for (String next : theBases) {
				if (isNotBlank(next)) {
					tokenOrListParam.addOr(new TokenParam(next));
				}
			}
		}

		if (retVal.getValuesAsQueryTokens().isEmpty()) {
			return null;
		}

		return retVal;
	}

	public List<String> getUpliftExtensions() {
		if (myUpliftExtensions == null) {
			myUpliftExtensions = new ArrayList<>();
		}
		return myUpliftExtensions;
	}
	public void addUpliftExtension(String theUrl) {
		getUpliftExtensions().add(theUrl);
	}
}
