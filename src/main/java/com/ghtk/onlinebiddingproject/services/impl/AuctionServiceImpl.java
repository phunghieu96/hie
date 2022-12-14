package com.ghtk.onlinebiddingproject.services.impl;

import com.ghtk.onlinebiddingproject.constants.AuctionStatusConstants;
import com.ghtk.onlinebiddingproject.constants.ReviewResultConstants;
import com.ghtk.onlinebiddingproject.daos.AuctionDao;
import com.ghtk.onlinebiddingproject.exceptions.BadRequestException;
import com.ghtk.onlinebiddingproject.exceptions.NotFoundException;
import com.ghtk.onlinebiddingproject.models.entities.*;
import com.ghtk.onlinebiddingproject.models.requests.AuctionRequestDto;
import com.ghtk.onlinebiddingproject.models.responses.AuctionPagingResponse;
import com.ghtk.onlinebiddingproject.models.responses.AuctionTopTrendingDto;
import com.ghtk.onlinebiddingproject.repositories.AuctionRepository;
import com.ghtk.onlinebiddingproject.repositories.ReviewResultRepository;
import com.ghtk.onlinebiddingproject.repositories.WinnerRepository;
import com.ghtk.onlinebiddingproject.security.UserDetailsImpl;
import com.ghtk.onlinebiddingproject.services.AuctionService;
import com.ghtk.onlinebiddingproject.utils.CurrentUserUtils;
import com.ghtk.onlinebiddingproject.utils.DtoToEntityUtils;
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
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class AuctionServiceImpl implements AuctionService {
    @Autowired
    private AuctionRepository auctionRepository;
    @Autowired
    private WinnerRepository winnerRepository;
    @Autowired
    private ReviewResultRepository reviewResultRepository;
    @Autowired
    private ItemServiceImpl itemService;
    @Autowired
    private NotificationServiceImpl notificationService;
    @Autowired
    private AuctionDao auctionDao;

    @Override
    public AuctionPagingResponse get(Specification<Auction> spec, HttpHeaders headers, Sort sort) {
        if (PaginationUtils.isPaginationRequested(headers)) {
            return helperGet(spec, PaginationUtils.buildPageRequest(headers, sort));
        } else {
            List<Auction> auctionEntities = helperGet(spec, sort);
            return new AuctionPagingResponse(auctionEntities.size(), 0, 0, 0, auctionEntities);
        }
    }

    @Override
    public List<AuctionTopTrendingDto> getTopTrending() {
        return auctionDao.getTopTrending();
    }

    @Override
    public List<Auction> getMyAuctions(AuctionStatusConstants status) {
        UserDetailsImpl userDetails = CurrentUserUtils.getCurrentUserDetails();
        if (status != null)
            return auctionRepository.findByUser_IdAndStatus(userDetails.getId(), status, Sort.by(Sort.Direction.DESC, "createdAt"));
        return auctionRepository.findByUser_Id(userDetails.getId(), Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    @Override
    public List<Auction> getWonAuctionsByUserId(Integer userId) {
        List<Winner> winners = winnerRepository.findByBid_User_Id(userId);
        return winners.stream().map(Winner::getAuction).collect(Collectors.toList());
    }

    @Override
    public List<Auction> getAuctionsByUserId(Integer userId) {
        return auctionRepository.findByUser_IdAndStatusNotIn(userId, List.of(AuctionStatusConstants.DRAFT, AuctionStatusConstants.PENDING), Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    @Override
    public Auction getById(Integer id) {
        Auction auction = auctionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Kh??ng t??m th???y auction v???i id n??y!"));
        boolean isPostedByCurrentUser = CurrentUserUtils.isPostedByCurrentUser(auction.getUser().getId());
        AuctionStatusConstants currentStatus = auction.getStatus();

        if ((currentStatus.equals(AuctionStatusConstants.PENDING) || currentStatus.equals(AuctionStatusConstants.DRAFT)) && isPostedByCurrentUser)
            return auction;
        if (currentStatus.equals(AuctionStatusConstants.OPENING))
            return auction;
        else throw new AccessDeniedException("Kh??ng th??? l???y th??ng tin c???a b??i ?????u gi?? v??o l??c n??y!");
    }

    @Override
    @Transactional(rollbackFor = {SQLException.class})
    public Auction save(AuctionRequestDto auctionDto, Auction auction, Item item) {
        UserDetailsImpl userDetails = CurrentUserUtils.getCurrentUserDetails();
        if (userDetails.isSuspended()) throw new AccessDeniedException("T??i kho???n c???a b???n ??ang b??? gi???i h???n!");
        User user = new User(userDetails.getId());
        auction.setUser(user);

        if (auctionDto.getTimeEnd().isBefore(auctionDto.getTimeStart()))
            throw new BadRequestException("Th???i gian b???t ?????u v?? k???t th??c ?????u gi?? kh??ng h???p l???!");
        if (auctionDto.getTimeEnd().isEqual(auctionDto.getTimeStart()))
            throw new BadRequestException("Th???i gian b???t ?????u v?? k???t th??c ?????u gi?? kh??ng h???p l???!");
        if (LocalDateTime.now().isAfter(auctionDto.getTimeStart()))
            throw new BadRequestException("Th???i gian b???t ?????u ?????u gi?? kh??ng h???p l???!");
        if (LocalDateTime.now().isAfter(auctionDto.getTimeEnd()))
            throw new BadRequestException("Th???i gian k???t th??c ?????u gi?? kh??ng h???p l???!");
        if (ChronoUnit.MINUTES.between(auctionDto.getTimeStart(), auctionDto.getTimeEnd()) > 2881)
            throw new BadRequestException("Th???i gian b???t ?????u v?? th???i gian k???t th??c kh??ng ???????c c??ch nhau qu?? 48 ti???ng!");

        auction.setHighestPrice(0.0);
        Auction newAuction = auctionRepository.save(auction);
        item.setAuction(newAuction);
        Item newItem = itemService.save(item);
        newAuction.setItem(newItem);
        return newAuction;
    }

    @Override
    @Transactional(rollbackFor = {SQLException.class})
    public Auction put(AuctionRequestDto auctionDto, Integer id) {
        Auction auction = getById(id);
        UserDetailsImpl userDetails = CurrentUserUtils.getCurrentUserDetails();
        if (userDetails.isSuspended()) throw new AccessDeniedException("T??i kho???n c???a b???n ??ang b??? gi???i h???n!");

        boolean isPostedByCurrentUser = CurrentUserUtils.isPostedByCurrentUser(auction.getUser().getId());
        AuctionStatusConstants currentStatus = auction.getStatus();
        AuctionStatusConstants newStatus = auctionDto.getStatus();
        if (!isPostedByCurrentUser)
            throw new AccessDeniedException("Ch??? admin v?? ch??? b??i ?????u gi?? m???i c?? quy???n s???a!");
        if (!currentStatus.equals(AuctionStatusConstants.DRAFT) && !currentStatus.equals(AuctionStatusConstants.PENDING))
            throw new AccessDeniedException("Kh??ng th??? th???c hi???n s???a b??i ?????u gi?? khi ???? v?? ??ang (ch???) ?????u gi??!");
        if (newStatus != null)
            throw new BadRequestException("Kh??ng th??? t??? ?? thay ?????i tr???ng th??i b??i ?????u gi??!");

        DtoToEntityUtils.copyNonNullProperties(auctionDto, auction);
        if (auctionDto.getCategory() != null) auction.setCategory(new Category(auctionDto.getCategory().getId()));
        return auctionRepository.save(auction);
    }

    @Override
    @Transactional(rollbackFor = {SQLException.class})
    public Auction submitPending(Integer id) {
        Auction auction = getById(id);
        UserDetailsImpl userDetails = CurrentUserUtils.getCurrentUserDetails();
        if (userDetails.isSuspended()) throw new AccessDeniedException("T??i kho???n c???a b???n ??ang b??? gi???i h???n!");

        boolean isPostedByCurrentUser = CurrentUserUtils.isPostedByCurrentUser(auction.getUser().getId());
        AuctionStatusConstants currentStatus = auction.getStatus();

        if (!isPostedByCurrentUser)
            throw new AccessDeniedException("Ch??? admin v?? ch??? b??i ?????u gi?? m???i c?? quy???n s???a!");
        if (!currentStatus.equals(AuctionStatusConstants.DRAFT))
            throw new AccessDeniedException("Kh??ng th??? th???c hi???n s???a b??i ?????u gi?? khi ???? v?? ??ang ?????u gi??!");
        if (LocalDateTime.now().isAfter(auction.getTimeEnd()) || LocalDateTime.now().isAfter(auction.getTimeStart()))
            throw new BadRequestException("Th???i gian b???t ?????u ho???c th???i gian k???t th??c c???a b??i ?????u gi?? kh??ng h???p l???!");
        if (auction.getItem() != null) {
            auction.setStatus(AuctionStatusConstants.PENDING);
            return auctionRepository.save(auction);
        } else throw new BadRequestException("Kh??ng th??? submit b??i ?????u gi?? khi ch??a c?? s???n ph???m!");
    }

    @Override
    @Transactional(rollbackFor = {SQLException.class})
    public void deleteById(Integer id) {
        Auction auction = getById(id);
        boolean isPostedByCurrentUser = CurrentUserUtils.isPostedByCurrentUser(auction.getUser().getId());
        AuctionStatusConstants currentStatus = auction.getStatus();

        if (!isPostedByCurrentUser)
            throw new AccessDeniedException("Ch??? admin v?? ch??? b??i ?????u gi?? m???i c?? quy???n s???a!");
        if (currentStatus.equals(AuctionStatusConstants.PENDING) || currentStatus.equals(AuctionStatusConstants.DRAFT)) {
            List<ItemImage> itemImages = auction.getItem().getItemImages();
            itemService.deleteItemImages(itemImages);
            notificationService.deleteAuctionNotifications(auction.getId());
            auctionRepository.delete(auction);
        } else throw new AccessDeniedException("Kh??ng th??? th???c hi???n xo?? b??i ?????u gi?? khi ???? v?? ??ang ?????u gi??!");
    }

    /**
     * For admin
     */

    @Override
    public Auction adminGetById(Integer id) {
        return auctionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Kh??ng t??m th???y auction v???i id n??y!"));
    }

    @Override
    public Auction adminPut(AuctionRequestDto auctionDto, Integer id) {
        Auction auction = adminGetById(id);
        DtoToEntityUtils.copyNonNullProperties(auctionDto, auction);
        return auctionRepository.save(auction);
    }

    @Override
    @Transactional(rollbackFor = {SQLException.class})
    public Auction adminReviewSubmit(AuctionRequestDto auctionRequestDto, Integer id) {
        UserDetailsImpl userDetails = CurrentUserUtils.getCurrentUserDetails();
        Auction auction = adminGetById(id);

        if (auction.getStatus().equals(AuctionStatusConstants.PENDING)) {
            Admin admin = new Admin(userDetails.getId());
            AuctionStatusConstants newStatus = auctionRequestDto.getStatus();
            ReviewResult reviewResult = null;
            if (newStatus.equals(AuctionStatusConstants.QUEUED))
                reviewResult = new ReviewResult(ReviewResultConstants.ACCEPTED, auction, admin);
            else if (newStatus.equals(AuctionStatusConstants.CANCELED))
                reviewResult = new ReviewResult(ReviewResultConstants.REJECTED, auction, admin);
            else throw new BadRequestException("Duy???t b??i ?????u gi?? kh??ng th??nh c??ng!");
            reviewResultRepository.save(reviewResult);
            auction.setStatus(newStatus);
            return auctionRepository.save(auction);
        }
        throw new BadRequestException("Ch??a th??? duy???t b??i ?????u gi?? n??y v??o l??c n??y!");
    }

    @Override
    public void adminDeleteById(Integer id) {
        notificationService.deleteAuctionNotifications(id);
        auctionRepository.deleteById(id);
    }

    /**
     * helper methods
     */

    public List<Auction> helperGet(Specification<Auction> spec, Sort sort) {
        return auctionRepository.findAll(spec, sort);
    }

    public AuctionPagingResponse helperGet(Specification<Auction> spec, Pageable pageable) {
        Page<Auction> page = auctionRepository.findAll(spec, pageable);
        List<Auction> auctionEntities = page.getContent();
        return new AuctionPagingResponse(
                (int) page.getTotalElements(),
                page.getNumber(),
                page.getNumberOfElements(),
                page.getTotalPages(),
                auctionEntities);
    }
}
