package ffdd.opsconsole.content.dto;

/** Only enabled, public client destinations; no URL, arbitrary path or administrative route. */
public record SupportLinkTarget(String type, java.util.Map<String,String> params) {}
