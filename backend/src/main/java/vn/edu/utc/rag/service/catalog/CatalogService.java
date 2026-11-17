package vn.edu.utc.rag.service.catalog;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.stereotype.Service;
import vn.edu.utc.rag.persistence.repository.query.CatalogQueryRepository;
import vn.edu.utc.rag.web.pagination.PageRequestFactory;

@Service
public class CatalogService {

  private final CatalogQueryRepository repository;

  private final PageRequestFactory pages;

  public CatalogService(CatalogQueryRepository repository, PageRequestFactory pages) {
    this.repository = repository;
    this.pages = pages;
  }

  public Object campuses(HttpServletRequest req) {
    return repository.campuses(pages.request(req));
  }

  public Object suggestions(HttpServletRequest req) {
    return repository.suggestions(pages.request(req));
  }

  public Object cycles(HttpServletRequest req) {
    return repository.cycles(pages.request(req));
  }

  public Object cycle(String id) {
    return repository.cycle(id);
  }

  public Object methods(String id, HttpServletRequest req) {
    return repository.methods(id, pages.request(req));
  }

  public Object options(String id, HttpServletRequest req) {
    return repository.options(id, pages.request(req));
  }

  public Object combinations(String id, HttpServletRequest req) {
    return repository.combinations(id, pages.request(req));
  }

  public Object cutoffs(HttpServletRequest req) {
    return repository.cutoffs(pages.request(req));
  }

  public Object programs(HttpServletRequest req) {
    return repository.programs(pages.request(req));
  }

  public Object program(String id) {
    return repository.program(id);
  }

  public Object offerings(HttpServletRequest req) {
    return repository.offerings(pages.request(req));
  }

  public Object curriculum(String id) {
    return repository.curriculum(id);
  }

  public Object courses(String id, HttpServletRequest req) {
    return repository.courses(id, pages.request(req));
  }

  public Object questionnaires(HttpServletRequest req) {
    return repository.questionnaires(pages.request(req));
  }

  public Object questions(String id, String version, HttpServletRequest req) {
    return repository.questions(id, version, pages.request(req));
  }

  public Object questionsForVersion(long version, HttpServletRequest req) {
    return repository.questionsForVersion(version, pages.request(req));
  }

  public Object guides(HttpServletRequest req) {
    return repository.guides(pages.request(req));
  }

  public Object guide(String id) {
    return repository.guide(id);
  }

  public Object entries(String id, HttpServletRequest req) {
    return repository.entries(id, pages.request(req));
  }

  public Object schedule(HttpServletRequest req) {
    return repository.schedule(pages.request(req));
  }

  public Object finances(HttpServletRequest req) {
    return repository.finances(pages.request(req));
  }

  public Object finance(String id) {
    return repository.finance(id);
  }
}
