package com.ghtk.onlinebiddingproject.services.impl;

import com.ghtk.onlinebiddingproject.constants.ReportResultConstants;
import com.ghtk.onlinebiddingproject.exceptions.BadRequestException;
import com.ghtk.onlinebiddingproject.exceptions.NotFoundException;
import com.ghtk.onlinebiddingproject.models.entities.*;
import com.ghtk.onlinebiddingproject.models.responses.ReportPagingResponse;
import com.ghtk.onlinebiddingproject.repositories.ReportImageRepository;
import com.ghtk.onlinebiddingproject.repositories.ReportRepository;
import com.ghtk.onlinebiddingproject.repositories.ReportResultRepository;
import com.ghtk.onlinebiddingproject.security.UserDetailsImpl;
import com.ghtk.onlinebiddingproject.services.ReportService;
import com.ghtk.onlinebiddingproject.utils.CurrentUserUtils;
import com.ghtk.onlinebiddingproject.utils.PaginationUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.List;

@Service
public class ReportServiceImpl implements ReportService {
    @Autowired
    private NotificationServiceImpl notificationService;
    @Autowired
    private ProfileServiceImpl profileService;
    @Autowired
    private ReportRepository reportRepository;
    @Autowired
    private ReportResultRepository reportResultRepository;
    @Autowired
    private ReportImageRepository reportImageRepository;

    @Override
    @Transactional(rollbackFor = {SQLException.class})
    public Report save(Report report) {
        UserDetailsImpl userDetails = CurrentUserUtils.getCurrentUserDetails();
        if (userDetails.isSuspended()) throw new AccessDeniedException("T??i kho???n c???a b???n ??ang b??? gi???i h???n!");

        Report newReport = reportRepository.save(report);
        List<ReportImage> reportImages = report.getReportImages();
        if (reportImages != null && reportImages.size() != 0) {
            for (ReportImage reportImage : reportImages) {
                reportImage.setReport(newReport);
                reportImageRepository.save(reportImage);
            }
        }
        return newReport;
    }

    /*
     * For admin
     * */

    @Override
    public ReportPagingResponse get(Specification<Report> spec, HttpHeaders headers, Sort sort) {
        if (PaginationUtils.isPaginationRequested(headers)) {
            return helperGet(spec, PaginationUtils.buildPageRequest(headers, sort));
        } else {
            List<Report> reportEntities = helperGet(spec, sort);
            return new ReportPagingResponse(reportEntities.size(), 0, 0, 0, reportEntities);
        }
    }

    @Override
    public Report adminGetById(Integer id) {
        return reportRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Kh??ng t??m th???y phi???u b??o c??o v???i id n??y!"));
    }

    @Override
    public void adminDeleteById(Integer id) {
        notificationService.deleteReportNotifications(id);
        reportRepository.deleteById(id);
    }

    @Override
    public List<Report> adminGetReports() {
        return reportRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    @Override
    @Transactional(rollbackFor = {SQLException.class})
    public ReportResult adminJudgeReport(Integer id, ReportResult reportResult) {
        Report report = adminGetById(id);
        ReportResult existingResult = reportResultRepository.findByReport_Id(report.getId());
        if (existingResult == null) {
            UserDetailsImpl userDetails = CurrentUserUtils.getCurrentUserDetails();
            reportResult.setAdmin(new Admin(userDetails.getId()));
            reportResult.setReport(report);

            //deduct legitmate score
            if (reportResult.getResult().equals(ReportResultConstants.ACCEPTED)) {
                profileService.deductLegitimateScore(report.getUserReported().getId(), 5);
            }
            return reportResultRepository.save(reportResult);
        } else throw new BadRequestException("Phi???u b??o c??o n??y ???? ???????c xem x??t tr?????c ????!");
    }

    /*
     * Report Images
     * */

    @Override
    public ReportImage saveReportImage(Integer id, ReportImage reportImage) {
        Report report = reportRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Kh??ng t??m th???y report v???i id n??y!"));
        boolean isPostedByCurrentUser = CurrentUserUtils.isPostedByCurrentUser(report.getUserReporter().getId());
        if (isPostedByCurrentUser) {
            reportImage.setReport(report);
            return reportImageRepository.save(reportImage);
        } else throw new AccessDeniedException("Kh??ng c?? quy???n th??m ???nh v??o phi???u b??o c??o n??y!");
    }

    /**
     * helper methods
     */

    public List<Report> helperGet(Specification<Report> spec, Sort sort) {
        return reportRepository.findAll(spec, sort);
    }

    public ReportPagingResponse helperGet(Specification<Report> spec, Pageable pageable) {
        Page<Report> page = reportRepository.findAll(spec, pageable);
        List<Report> reportEntities = page.getContent();
        return new ReportPagingResponse((int) page.getTotalElements(), page.getNumber(), page.getNumberOfElements(), page.getTotalPages(), reportEntities);
    }
}
