package com.otr.portal.oss.model.usb;

/**
 * Организация в ответе USS (identification v2).
 */
public record UsbOrganization(
        String code,
        String name,
        String shortName,
        String fullName,
        String regionId,
        String regionCode,
        Integer type,
        String inn,
        String kpp,
        String ogrn,
        String parentId,
        String parentCode,
        String parentName,
        Integer status,
        String from,
        String to,
        String fullPathCodes,
        String fullPathNames
) {
}
